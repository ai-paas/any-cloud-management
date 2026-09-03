// PodExec orchestrator 회귀 보호 (allowlist 거부 / namespace 화이트리스트).
//
// 본 테스트는 k8s.Client 를 stub 으로 주입하므로 실제 K8s API 호출 없음. Backend gRPC 도
// in-process AgentRuntime stub server 로 대체 — 첫 ExecPacket 만 검증.
package exec

import (
	"context"
	"errors"
	"net"
	"sync"
	"testing"
	"time"

	"anycloud/agent/internal/config"
	agentv1 "anycloud/agent/internal/gen/agent/v1"
	"anycloud/agent/internal/k8s"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/fake"
	"google.golang.org/grpc"
)

// ---- in-process AgentRuntime stub ----

type execStub struct {
	agentv1.UnimplementedAgentRuntimeServer

	mu          sync.Mutex
	firstPacket *agentv1.ExecPacket
	endPacket   *agentv1.ExecPacket

	firstReceived chan struct{}
	endReceived   chan struct{}
}

func newExecStub() *execStub {
	return &execStub{
		firstReceived: make(chan struct{}, 1),
		endReceived:   make(chan struct{}, 1),
	}
}

func (s *execStub) PodExec(stream agentv1.AgentRuntime_PodExecServer) error {
	for {
		pkt, err := stream.Recv()
		if err != nil {
			return nil
		}
		s.mu.Lock()
		switch pkt.GetPayload().(type) {
		case *agentv1.ExecPacket_Request:
			if s.firstPacket == nil {
				s.firstPacket = pkt
				select {
				case s.firstReceived <- struct{}{}:
				default:
				}
			}
		case *agentv1.ExecPacket_End:
			if s.endPacket == nil {
				s.endPacket = pkt
				select {
				case s.endReceived <- struct{}{}:
				default:
				}
			}
		}
		s.mu.Unlock()
	}
}

func startExecServer(t *testing.T) (string, *execStub, func()) {
	t.Helper()
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	srv := grpc.NewServer()
	stub := newExecStub()
	agentv1.RegisterAgentRuntimeServer(srv, stub)
	go func() { _ = srv.Serve(lis) }()
	return lis.Addr().String(), stub, func() {
		srv.GracefulStop()
		_ = lis.Close()
	}
}

// ---- k8s.Client stub — ExecInPod 의 동작만 검증 ----

type kubeStub struct {
	k8s.Client     // embed to inherit unused method nil-ptr behavior.
	executed       chan k8s.PodExecOptions
	returnErr      error
	consumeStreams bool
}

func (k *kubeStub) ExecInPod(ctx context.Context, opts k8s.PodExecOptions, streams k8s.ExecStreams) error {
	if k.executed != nil {
		k.executed <- opts
	}
	if k.consumeStreams && streams.Stdin != nil {
		buf := make([]byte, 1)
		_, _ = streams.Stdin.Read(buf)
	}
	return k.returnErr
}

// ---- helpers ----

func denyAllLoader(t *testing.T) *config.Loader {
	t.Helper()
	cs := fake.NewSimpleClientset()
	loader := config.NewLoader(cs, "aipaas-system", "missing-cm")
	_ = loader.LoadOnce(context.Background())     // CM not found → deny-all.
	return loader
}

func permissiveLoader(t *testing.T, execNamespaces ...string) *config.Loader {
	t.Helper()
	nss := ""
	for _, n := range execNamespaces {
		nss += "- " + n + "\n"
	}
	cm := &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{Name: "aipaas-agent-allowlist", Namespace: "aipaas-system"},
		Data: map[string]string{
			"allowed_commands":        "- EXEC_POD\n",
			"allowed_exec_namespaces": nss,
		},
	}
	cs := fake.NewSimpleClientset(cm)
	loader := config.NewLoader(cs, "aipaas-system", "aipaas-agent-allowlist")
	if err := loader.LoadOnce(context.Background()); err != nil {
		t.Fatalf("LoadOnce: %v", err)
	}
	return loader
}

// ---- tests ----

func TestRun_AllowListDeniesNamespace_SendsEndPacket(t *testing.T) {
	addr, stub, cleanup := startExecServer(t)
	defer cleanup()

	kube := &kubeStub{executed: make(chan k8s.PodExecOptions, 1)}
	runner := New(kube, permissiveLoader(t, "default"))     // "kube-system" not allowed.

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	err := runner.Run(ctx, SessionConfig{
		BackendAddr:        addr,
		AgentIdentityToken: "tok-1",
		SessionID:          "sess-deny",
		Request: &agentv1.ExecRequest{
			SessionId: "sess-deny",
			Namespace: "kube-system",     // denied.
			Pod:       "etcd-0",
			Command:   []string{"/bin/sh"},
		},
	})
	if err != nil {
		t.Fatalf("Run unexpected error: %v", err)
	}

	// 첫 packet (Request) 도착 확인.
	select {
	case <-stub.firstReceived:
	case <-time.After(2 * time.Second):
		t.Fatal("first ExecPacket never arrived")
	}
	// End packet 으로 거부 신호 검증.
	select {
	case <-stub.endReceived:
	case <-time.After(2 * time.Second):
		t.Fatal("End ExecPacket never arrived")
	}
	stub.mu.Lock()
	defer stub.mu.Unlock()
	if stub.endPacket.GetEnd().GetErrorCode() != "NAMESPACE_DENIED" {
		t.Errorf("error code = %q, want NAMESPACE_DENIED", stub.endPacket.GetEnd().GetErrorCode())
	}
	// k8s ExecInPod 는 호출 안 되어야 함.
	select {
	case opts := <-kube.executed:
		t.Fatalf("ExecInPod should not be called, got %+v", opts)
	default:
	}
}

func TestRun_PermittedNamespace_CallsExecInPod(t *testing.T) {
	addr, stub, cleanup := startExecServer(t)
	defer cleanup()

	kube := &kubeStub{executed: make(chan k8s.PodExecOptions, 1)}
	runner := New(kube, permissiveLoader(t, "default"))

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	go func() {
		_ = runner.Run(ctx, SessionConfig{
			BackendAddr:        addr,
			AgentIdentityToken: "tok-1",
			SessionID:          "sess-ok",
			Request: &agentv1.ExecRequest{
				SessionId: "sess-ok",
				Namespace: "default",
				Pod:       "web-1",
				Container: "main",
				Command:   []string{"/bin/bash"},
				Tty:       true,
				Stdin:     true,
			},
		})
	}()

	select {
	case opts := <-kube.executed:
		if opts.Namespace != "default" || opts.Pod != "web-1" || opts.Container != "main" {
			t.Errorf("opts = %+v", opts)
		}
		if !opts.TTY {
			t.Error("TTY should be true")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("ExecInPod was never called")
	}

	// 첫 packet 검증.
	select {
	case <-stub.firstReceived:
	case <-time.After(2 * time.Second):
		t.Fatal("first ExecPacket never arrived")
	}
	stub.mu.Lock()
	got := stub.firstPacket.GetRequest()
	stub.mu.Unlock()
	if got.GetSessionId() != "sess-ok" || got.GetPod() != "web-1" {
		t.Errorf("first packet wrong: %+v", got)
	}
}

func TestRun_K8sExecFails_SendsEndWithFailedStatus(t *testing.T) {
	addr, stub, cleanup := startExecServer(t)
	defer cleanup()

	kube := &kubeStub{
		executed:  make(chan k8s.PodExecOptions, 1),
		returnErr: errors.New("pod not found"),
	}
	runner := New(kube, permissiveLoader(t, "default"))

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	err := runner.Run(ctx, SessionConfig{
		BackendAddr:        addr,
		AgentIdentityToken: "tok-1",
		SessionID:          "sess-fail",
		Request: &agentv1.ExecRequest{
			SessionId: "sess-fail",
			Namespace: "default",
			Pod:       "ghost",
			Command:   []string{"/bin/sh"},
		},
	})
	if err != nil {
		t.Fatalf("Run: %v", err)
	}

	select {
	case <-stub.endReceived:
	case <-time.After(2 * time.Second):
		t.Fatal("End packet never sent")
	}
	stub.mu.Lock()
	defer stub.mu.Unlock()
	if stub.endPacket.GetEnd().GetErrorCode() != "EXEC_FAILED" {
		t.Errorf("error code = %q, want EXEC_FAILED", stub.endPacket.GetEnd().GetErrorCode())
	}
	if stub.endPacket.GetEnd().GetExitCode() != -1 {
		t.Errorf("exit code = %d, want -1", stub.endPacket.GetEnd().GetExitCode())
	}
}

func TestRun_DenyAllAllowList_RefusesEvenWithRequest(t *testing.T) {
	addr, stub, cleanup := startExecServer(t)
	defer cleanup()

	kube := &kubeStub{executed: make(chan k8s.PodExecOptions, 1)}
	runner := New(kube, denyAllLoader(t))

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	_ = runner.Run(ctx, SessionConfig{
		BackendAddr:        addr,
		AgentIdentityToken: "tok-1",
		SessionID:          "sess-deny-all",
		Request: &agentv1.ExecRequest{
			SessionId: "sess-deny-all",
			Namespace: "default",
			Pod:       "p",
			Command:   []string{"/bin/sh"},
		},
	})

	select {
	case <-stub.endReceived:
	case <-time.After(2 * time.Second):
		t.Fatal("End packet never sent on deny-all")
	}
	stub.mu.Lock()
	defer stub.mu.Unlock()
	code := stub.endPacket.GetEnd().GetErrorCode()
	// deny-all → namespace check fails first (because allowed_exec_namespaces empty).
	if code != "NAMESPACE_DENIED" && code != "PERMISSION_DENIED" {
		t.Errorf("error code = %q, want NAMESPACE_DENIED or PERMISSION_DENIED", code)
	}
}
