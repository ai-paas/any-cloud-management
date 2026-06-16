package core

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"

	agentv1 "anycloud/agent/internal/gen/agent/v1"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

// In-process backend stub — net.Listen + grpc.Server. 실제 네트워크 dial 까지 검증.

type stubBackend struct {
	agentv1.UnimplementedAgentBootstrapServer

	expectedToken string
	// 마지막 호출에서 본 request — 검증용.
	lastRequest *agentv1.RegisterRequest
	// 마지막 호출에서 Bearer header.
	lastBearer string

	// fail 토글.
	rejectToken bool
}

func (s *stubBackend) Register(ctx context.Context, req *agentv1.RegisterRequest) (*agentv1.RegisterResponse, error) {
	md, _ := metadata.FromIncomingContext(ctx)
	if auths := md.Get("authorization"); len(auths) > 0 {
		s.lastBearer = auths[0]
	}
	s.lastRequest = req
	if s.rejectToken {
		return nil, status.Error(codes.PermissionDenied, "invalid token")
	}
	return &agentv1.RegisterResponse{
		ClusterId:          "demo-aws-01",
		AgentIdentityToken: "abcdef1234567890",
		ExpiresAt:          "2026-07-12T00:00:00Z",
		ClusterStatus:      agentv1.ClusterStatus_CLUSTER_STATUS_ACTIVE,
	}, nil
}

func startServer(t *testing.T, stub *stubBackend) (addr string, cleanup func()) {
	t.Helper()
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	srv := grpc.NewServer()
	agentv1.RegisterAgentBootstrapServer(srv, stub)
	go func() { _ = srv.Serve(lis) }()
	return lis.Addr().String(), func() {
		srv.GracefulStop()
		_ = lis.Close()
	}
}

func baseConfig(addr, token string) BootstrapConfig {
	return BootstrapConfig{
		BackendAddr:       addr,
		RegistrationToken: token,
		KubernetesUID:     "550e8400-e29b-41d4-a716-446655440000",
		KubernetesVersion: "1.34.3",
		Distribution:      "kubeadm",
		APIServerEndpoint: "https://10.0.0.1:6443",
		AgentInstanceID:   "instance-1",
		AgentVersion:      "1.0.0",
		PodName:           "aipaas-agent-test",
		PublicIP:          "1.2.3.4",
		PrivateIP:         "10.0.0.1",
		DialTimeout:       3 * time.Second,
		RegisterTimeout:   3 * time.Second,
	}
}

func TestRun_SuccessfulRegister(t *testing.T) {
	stub := &stubBackend{}
	addr, cleanup := startServer(t, stub)
	defer cleanup()

	result, err := Run(context.Background(), baseConfig(addr, "fake-jwt-token"))
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if result.ClusterID != "demo-aws-01" {
		t.Errorf("ClusterID = %q, want demo-aws-01", result.ClusterID)
	}
	if result.AgentIdentityToken != "abcdef1234567890" {
		t.Errorf("AgentIdentityToken = %q, want abcdef1234567890", result.AgentIdentityToken)
	}
	if result.ClusterStatus != agentv1.ClusterStatus_CLUSTER_STATUS_ACTIVE {
		t.Errorf("ClusterStatus = %v, want ACTIVE", result.ClusterStatus)
	}

	// Bearer 헤더 검증 — registration_token 이 metadata 로 전달되었는지.
	if stub.lastBearer != "Bearer fake-jwt-token" {
		t.Errorf("backend received bearer = %q, want 'Bearer fake-jwt-token'", stub.lastBearer)
	}
	// Request payload 검증.
	if stub.lastRequest.GetCluster().GetK8SClusterUid() != "550e8400-e29b-41d4-a716-446655440000" {
		t.Errorf("K8sClusterUid not forwarded: got %q", stub.lastRequest.GetCluster().GetK8SClusterUid())
	}
	if stub.lastRequest.GetAgent().GetAgentInstanceId() != "instance-1" {
		t.Errorf("AgentInstanceId not forwarded")
	}
}

func TestRun_RejectsMissingToken(t *testing.T) {
	cfg := baseConfig("127.0.0.1:1", "")
	_, err := Run(context.Background(), cfg)
	if err == nil {
		t.Fatal("expected error for missing token")
	}
}

func TestRun_RejectsMissingBackend(t *testing.T) {
	cfg := baseConfig("", "fake-token")
	_, err := Run(context.Background(), cfg)
	if err == nil {
		t.Fatal("expected error for missing backend addr")
	}
}

func TestRun_BackendRejectsToken_PropagatesError(t *testing.T) {
	stub := &stubBackend{rejectToken: true}
	addr, cleanup := startServer(t, stub)
	defer cleanup()

	_, err := Run(context.Background(), baseConfig(addr, "bad-token"))
	if err == nil {
		t.Fatal("expected error from backend PERMISSION_DENIED")
	}
	st, ok := status.FromError(errors.Unwrap(err))
	if !ok {
		// fmt.Errorf wrapping — direct check on string.
		if !contains(err.Error(), "PermissionDenied") && !contains(err.Error(), "invalid token") {
			t.Errorf("expected PermissionDenied error, got: %v", err)
		}
		return
	}
	if st.Code() != codes.PermissionDenied {
		t.Errorf("code = %v, want PermissionDenied", st.Code())
	}
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}
