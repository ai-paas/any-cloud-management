// rotation timer + TokenStore 회귀.
package core

import (
	"context"
	"net"
	"sync"
	"testing"
	"time"

	agentv1 "anycloud/agent/internal/gen/agent/v1"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

// ---- TokenStore unit ----

func TestTokenStore_SetEmitsReconnectSignal(t *testing.T) {
	s := NewTokenStore("v1", time.Now())
	signal := s.ReconnectSignal()

	// 초기엔 signal 없음.
	select {
	case <-signal:
		t.Fatal("signal should be empty initially")
	default:
	}

	s.Set("v2", time.Now())
	select {
	case <-signal:
		// good
	case <-time.After(100 * time.Millisecond):
		t.Fatal("Set should emit reconnect signal")
	}
}

func TestTokenStore_MultipleSets_SignalChannelBuffered(t *testing.T) {
	// channel 가득차면 drop OK — 누락된 signal 도 다음 stream 가 받음.
	s := NewTokenStore("v1", time.Now())
	for i := 0; i < 10; i++ {
		s.Set("v"+string(rune('0'+i)), time.Now())
	}
	select {
	case <-s.ReconnectSignal():
		// good
	case <-time.After(100 * time.Millisecond):
		t.Fatal("at least one signal should be pending")
	}
}

func TestTokenStore_GetSet_ConcurrentSafe(t *testing.T) {
	now := time.Now()
	s := NewTokenStore("initial", now)
	tok, exp := s.Get()
	if tok != "initial" || !exp.Equal(now) {
		t.Errorf("initial get failed")
	}
	var wg sync.WaitGroup
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			s.Set("tok-"+string(rune('A'+i)), now.Add(time.Duration(i)*time.Hour))
		}(i)
	}
	wg.Wait()
	tok, _ = s.Get()
	if tok == "" {
		t.Errorf("token should be set")
	}
}

// ---- Bootstrap stub with RotateIdentityToken ----

type bootstrapStub struct {
	agentv1.UnimplementedAgentBootstrapServer

	mu               sync.Mutex
	calls            int
	lastBearer       string
	lastInstanceID   string
	responseToken    string
	responseExp      string
	rotateShouldFail bool
}

func (s *bootstrapStub) RotateIdentityToken(ctx context.Context, req *agentv1.RotateRequest) (*agentv1.RotateResponse, error) {
	md, _ := metadata.FromIncomingContext(ctx)
	s.mu.Lock()
	s.calls++
	if auths := md.Get("authorization"); len(auths) > 0 {
		s.lastBearer = auths[0]
	}
	s.lastInstanceID = req.GetAgentInstanceId()
	shouldFail := s.rotateShouldFail
	tok := s.responseToken
	exp := s.responseExp
	s.mu.Unlock()

	if shouldFail {
		return nil, status.Error(codes.PermissionDenied, "token revoked")
	}
	return &agentv1.RotateResponse{NewIdentityToken: tok, ExpiresAt: exp}, nil
}

func startBootstrapStub(t *testing.T, stub *bootstrapStub) (string, func()) {
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

// ---- rotateOnce 회귀 ----

func TestRotateOnce_HappyPath_UpdatesToken(t *testing.T) {
	newExp := time.Now().Add(60 * 24 * time.Hour)
	stub := &bootstrapStub{
		responseToken: "rotated-token-xyz",
		responseExp:   newExp.UTC().Format(time.RFC3339),
	}
	addr, cleanup := startBootstrapStub(t, stub)
	defer cleanup()

	store := NewTokenStore("current-token", time.Now().Add(2*time.Hour))
	cfg := RotationConfig{
		BackendAddr:     addr,
		AgentInstanceID: "instance-1",
	}
	tok, exp, err := rotateOnce(context.Background(), cfg, store)
	if err != nil {
		t.Fatalf("rotateOnce: %v", err)
	}
	if tok != "rotated-token-xyz" {
		t.Errorf("token = %q, want rotated-token-xyz", tok)
	}
	// 만료 시각이 응답과 ±1초 안.
	if exp.Sub(newExp).Abs() > time.Second {
		t.Errorf("exp = %v, want ≈ %v", exp, newExp)
	}
	if stub.lastBearer != "Bearer current-token" {
		t.Errorf("bearer = %q, want 'Bearer current-token'", stub.lastBearer)
	}
	if stub.lastInstanceID != "instance-1" {
		t.Errorf("instance id = %q", stub.lastInstanceID)
	}
}

func TestRotateOnce_PermissionDenied_ReturnsRevokedError(t *testing.T) {
	stub := &bootstrapStub{rotateShouldFail: true}
	addr, cleanup := startBootstrapStub(t, stub)
	defer cleanup()

	store := NewTokenStore("current", time.Now().Add(time.Hour))
	cfg := RotationConfig{BackendAddr: addr}
	_, _, err := rotateOnce(context.Background(), cfg, store)
	if err == nil {
		t.Fatal("expected error")
	}
}

func TestRotateOnce_EmptyToken_ReturnsError(t *testing.T) {
	store := NewTokenStore("", time.Now())
	_, _, err := rotateOnce(context.Background(), RotationConfig{BackendAddr: "127.0.0.1:1"}, store)
	if err == nil {
		t.Fatal("expected error for empty token")
	}
}

func TestRunRotation_AppliesNewTokenToStore(t *testing.T) {
	newExp := time.Now().Add(60 * 24 * time.Hour)
	stub := &bootstrapStub{
		responseToken: "rotated-1",
		responseExp:   newExp.UTC().Format(time.RFC3339),
	}
	addr, cleanup := startBootstrapStub(t, stub)
	defer cleanup()

	// expiresAt 가 이미 임박 (1분 후) — 즉시 rotation 발화.
	store := NewTokenStore("starter", time.Now().Add(time.Minute))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	cfg := DefaultRotationConfig()
	cfg.BackendAddr = addr
	cfg.AgentInstanceID = "i-1"
	cfg.CheckInterval = 100 * time.Millisecond
	cfg.RetryInterval = 100 * time.Millisecond

	rotated := make(chan string, 1)
	go RunRotation(ctx, cfg, store, nil, "", func(newToken string, expiresAt time.Time) {
		select {
		case rotated <- newToken:
		default:
		}
	})

	select {
	case tok := <-rotated:
		if tok != "rotated-1" {
			t.Errorf("rotated = %q, want rotated-1", tok)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("rotation callback not called within 3s")
	}
	// store 업데이트 확인.
	stored, _ := store.Get()
	if stored != "rotated-1" {
		t.Errorf("store token = %q, want rotated-1", stored)
	}
}
