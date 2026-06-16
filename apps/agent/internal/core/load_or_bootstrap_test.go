// BootstrapIdentity 회귀 — Secret 의 valid token 가 있으면 Register 건너뜀.
package core

import (
	"context"
	"testing"
	"time"
)

func TestLoadValidIdentity_SkipsRegister(t *testing.T) {
	store := &InMemoryIdentityStore{}
	// valid token 사전 주입.
	preset := &IdentityMaterial{
		IdentityToken: "persisted-token-abc",
		ExpiresAt:     time.Now().Add(30 * 24 * time.Hour).UTC().Format(time.RFC3339),
		ClusterId:     "demo-cluster",
	}
	if err := store.Save(context.Background(), preset); err != nil {
		t.Fatalf("preset Save: %v", err)
	}

	// 만약 Register 가 호출되면 BackendAddr 가 unreachable 이라 에러 — 호출 안 되는 게 정상.
	cfg := BootstrapConfig{
		BackendAddr:       "127.0.0.1:1", // not listening — would fail if dialed
		RegistrationToken: "",            // 비어 있어도 OK (store 에 valid token 있음)
		DialTimeout:       1 * time.Second,
		RegisterTimeout:   1 * time.Second,
	}

	result, err := BootstrapIdentity(context.Background(), cfg, store, 5*time.Minute)
	if err != nil {
		t.Fatalf("BootstrapIdentity: %v (Register should be skipped)", err)
	}
	if result.AgentIdentityToken != "persisted-token-abc" {
		t.Errorf("AgentIdentityToken = %q, want persisted-token-abc", result.AgentIdentityToken)
	}
	if result.ClusterID != "demo-cluster" {
		t.Errorf("ClusterID = %q, want demo-cluster", result.ClusterID)
	}
}

func TestExpiredIdentity_FallsThroughToRegister(t *testing.T) {
	store := &InMemoryIdentityStore{}
	expired := &IdentityMaterial{
		IdentityToken: "expired-token",
		ExpiresAt:     time.Now().Add(-1 * time.Hour).UTC().Format(time.RFC3339),
		ClusterId:     "demo-cluster",
	}
	_ = store.Save(context.Background(), expired)

	// stubBackend (bootstrap_test.go 의 in-process gRPC) 띄워서 Register 가 호출되는지 검증.
	stub := &stubBackend{}
	addr, cleanup := startServer(t, stub)
	defer cleanup()

	cfg := baseConfig(addr, "fresh-registration-jwt")

	result, err := BootstrapIdentity(context.Background(), cfg, store, 5*time.Minute)
	if err != nil {
		t.Fatalf("BootstrapIdentity: %v", err)
	}
	// Register stub 가 돌려준 token 이어야 함.
	if result.AgentIdentityToken != "abcdef1234567890" {
		t.Errorf("AgentIdentityToken = %q, want stub-returned 'abcdef1234567890'", result.AgentIdentityToken)
	}
	if stub.lastBearer != "Bearer fresh-registration-jwt" {
		t.Errorf("Register not called with REGISTRATION_TOKEN; bearer = %q", stub.lastBearer)
	}
	// Save 가 호출되어 새 token 이 store 에 들어갔는지.
	saved, _ := store.Load(context.Background())
	if saved == nil || saved.IdentityToken != "abcdef1234567890" {
		t.Errorf("store not updated with new token after Register; got %+v", saved)
	}
}

func TestMissingIdentity_FallsThroughToRegister(t *testing.T) {
	store := &InMemoryIdentityStore{} // empty
	stub := &stubBackend{}
	addr, cleanup := startServer(t, stub)
	defer cleanup()

	cfg := baseConfig(addr, "first-boot-jwt")
	result, err := BootstrapIdentity(context.Background(), cfg, store, 5*time.Minute)
	if err != nil {
		t.Fatalf("BootstrapIdentity: %v", err)
	}
	if result.AgentIdentityToken == "" {
		t.Fatal("expected token from Register stub")
	}
	if stub.lastRequest == nil {
		t.Fatal("Register stub not called")
	}
	// Save 된 material 검증.
	saved, _ := store.Load(context.Background())
	if saved == nil {
		t.Fatal("store should be populated after first Register")
	}
}

func TestBootstrapIdentity_NilStore_StillBootstraps(t *testing.T) {
	stub := &stubBackend{}
	addr, cleanup := startServer(t, stub)
	defer cleanup()

	cfg := baseConfig(addr, "tok")
	result, err := BootstrapIdentity(context.Background(), cfg, nil, 5*time.Minute)
	if err != nil {
		t.Fatalf("nil store should still bootstrap: %v", err)
	}
	if result.AgentIdentityToken == "" {
		t.Fatal("expected token from Register")
	}
}
