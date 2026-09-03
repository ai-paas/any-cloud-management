package core

import (
	"context"
	"net"
	"sync"
	"testing"
	"time"

	"anycloud/agent/internal/controller"
	agentv1 "anycloud/agent/internal/gen/agent/v1"
	"google.golang.org/grpc"
	"google.golang.org/grpc/metadata"
	"google.golang.org/protobuf/types/known/structpb"
)

// In-process backend AgentRuntime stub — bidi stream.

type runtimeStub struct {
	agentv1.UnimplementedAgentRuntimeServer

	mu sync.Mutex
	// Last bearer seen.
	bearer string
	// Received heartbeats / responses.
	heartbeats int
	responses  []*agentv1.CommandResponse

	// Backend → agent commands queued by test.
	pendingCommands chan *agentv1.ControlMessage

	// Stream open notification.
	streamOpened chan struct{}
}

func newRuntimeStub() *runtimeStub {
	return &runtimeStub{
		pendingCommands: make(chan *agentv1.ControlMessage, 16),
		streamOpened:    make(chan struct{}, 1),
	}
}

func (s *runtimeStub) Stream(stream agentv1.AgentRuntime_StreamServer) error {
	md, _ := metadata.FromIncomingContext(stream.Context())
	s.mu.Lock()
	if auths := md.Get("authorization"); len(auths) > 0 {
		s.bearer = auths[0]
	}
	s.mu.Unlock()

	// Signal that stream is open.
	select {
	case s.streamOpened <- struct{}{}:
	default:
	}

	// 두 goroutine: 명령 push + 응답 / heartbeat recv.
	done := make(chan struct{})

	go func() {
		for {
			select {
			case <-stream.Context().Done():
				return
			case <-done:
				return
			case cmd := <-s.pendingCommands:
				_ = stream.Send(cmd)
			}
		}
	}()

	for {
		msg, err := stream.Recv()
		if err != nil {
			close(done)
			return err
		}
		s.mu.Lock()
		switch msg.GetPayload().(type) {
		case *agentv1.AgentMessage_Heartbeat:
			s.heartbeats++
		case *agentv1.AgentMessage_Response:
			s.responses = append(s.responses, msg.GetResponse())
		}
		s.mu.Unlock()
	}
}

func startRuntimeServer(t *testing.T, stub *runtimeStub) (addr string, cleanup func()) {
	t.Helper()
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	srv := grpc.NewServer()
	agentv1.RegisterAgentRuntimeServer(srv, stub)
	go func() { _ = srv.Serve(lis) }()
	return lis.Addr().String(), func() {
		srv.GracefulStop()
		_ = lis.Close()
	}
}

func runtimeTestConfig(addr, token string) RuntimeConfig {
	cfg := DefaultRuntimeConfig()
	cfg.BackendAddr = addr
	cfg.AgentIdentityToken = token
	cfg.AgentInstanceID = "instance-1"
	cfg.HeartbeatInterval = 0     // disabled — test 가 명시적으로 control.
	cfg.DialTimeout = 2 * time.Second
	cfg.InitialBackoff = 50 * time.Millisecond
	cfg.MaxBackoff = 500 * time.Millisecond
	return cfg
}

func TestRunStream_CommandRoundTrip(t *testing.T) {
	stub := newRuntimeStub()
	addr, cleanup := startRuntimeServer(t, stub)
	defer cleanup()

	dispatcher := controller.New("instance-1", "", nil, nil, nil)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// RunStream 을 background 에서 실행.
	streamErr := make(chan error, 1)
	go func() {
		streamErr <- RunStream(ctx, runtimeTestConfig(addr, "token-abc"), dispatcher, nil, nil, nil)
	}()

	// stream open 까지 wait.
	select {
	case <-stub.streamOpened:
	case <-time.After(2 * time.Second):
		t.Fatal("stream did not open")
	}

	// Bearer 검증.
	stub.mu.Lock()
	bearer := stub.bearer
	stub.mu.Unlock()
	if bearer != "Bearer token-abc" {
		t.Errorf("bearer = %q, want 'Bearer token-abc'", bearer)
	}

	// LIST_PODS command 보냄.
	params, _ := structpb.NewStruct(map[string]interface{}{"namespace": "web"})
	stub.pendingCommands <- &agentv1.ControlMessage{
		RequestId: "req-1",
		Payload: &agentv1.ControlMessage_Command{
			Command: &agentv1.CommandRequest{
				Type:   agentv1.CommandType_LIST_PODS,
				Params: params,
			},
		},
	}

	// Agent 가 응답 보낼 때까지 대기.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		stub.mu.Lock()
		got := len(stub.responses)
		stub.mu.Unlock()
		if got > 0 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	stub.mu.Lock()
	defer stub.mu.Unlock()
	if len(stub.responses) != 1 {
		t.Fatalf("responses len = %d, want 1", len(stub.responses))
	}
	resp := stub.responses[0]
	// Dispatcher 가 deps nil (k8s/helm/allowlist 모두 nil) — 본 테스트는 round-trip mechanics
	// 만 검증, status 는 PERMISSION_DENIED (allowlist deny-all) 또는 AGENT_UNAVAILABLE 둘 다 OK.
	// 실 명령 동작은 dispatcher_test 가 담당.
	st := resp.GetStatus()
	if st != agentv1.Status_PERMISSION_DENIED && st != agentv1.Status_AGENT_UNAVAILABLE && st != agentv1.Status_OK {
		t.Errorf("response status = %v (want OK / AGENT_UNAVAILABLE / PERMISSION_DENIED)", st)
	}

	cancel()
	<-streamErr     // wait for RunStream to exit.
}

func TestRunStream_HeartbeatSentPeriodically(t *testing.T) {
	stub := newRuntimeStub()
	addr, cleanup := startRuntimeServer(t, stub)
	defer cleanup()

	dispatcher := controller.New("instance-1", "", nil, nil, nil)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	cfg := runtimeTestConfig(addr, "token")
	cfg.HeartbeatInterval = 100 * time.Millisecond

	streamErr := make(chan error, 1)
	go func() { streamErr <- RunStream(ctx, cfg, dispatcher, nil, nil, nil) }()

	<-stub.streamOpened
	// 3 heartbeats 충분히 대기 (~300ms).
	time.Sleep(450 * time.Millisecond)

	stub.mu.Lock()
	got := stub.heartbeats
	stub.mu.Unlock()
	if got < 2 {
		t.Errorf("heartbeats = %d, want >= 2", got)
	}

	cancel()
	<-streamErr
}

func TestRunStream_ContextCanceled_ReturnsCanceled(t *testing.T) {
	stub := newRuntimeStub()
	addr, cleanup := startRuntimeServer(t, stub)
	defer cleanup()

	dispatcher := controller.New("instance-1", "", nil, nil, nil)
	ctx, cancel := context.WithCancel(context.Background())

	streamErr := make(chan error, 1)
	go func() { streamErr <- RunStream(ctx, runtimeTestConfig(addr, "token"), dispatcher, nil, nil, nil) }()

	<-stub.streamOpened
	cancel()

	select {
	case err := <-streamErr:
		if err != context.Canceled {
			t.Errorf("err = %v, want context.Canceled", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("RunStream did not exit on context cancel")
	}
}

func TestRunStream_RejectsEmptyToken(t *testing.T) {
	dispatcher := controller.New("instance-1", "", nil, nil, nil)
	cfg := runtimeTestConfig("127.0.0.1:1", "")     // empty token.

	err := RunStream(context.Background(), cfg, dispatcher, nil, nil, nil)
	if err == nil {
		t.Fatal("expected error for empty token")
	}
}

func TestNextBackoff_CapsAtMax(t *testing.T) {
	got := nextBackoff(5*time.Second, 10*time.Second, 3.0)
	if got != 10*time.Second {
		t.Errorf("got %v, want 10s", got)
	}
}

func TestNextBackoff_Multiplies(t *testing.T) {
	got := nextBackoff(1*time.Second, 30*time.Second, 2.0)
	if got != 2*time.Second {
		t.Errorf("got %v, want 2s", got)
	}
}
