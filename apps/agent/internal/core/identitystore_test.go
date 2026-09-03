// Identity token storage 회귀.
package core

import (
	"context"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/fake"
)

// ---- IsValid 단위 ----

func TestIsValid_NotExpired_ReturnsTrue(t *testing.T) {
	m := &IdentityMaterial{
		IdentityToken: "tok",
		ExpiresAt:     time.Now().Add(48 * time.Hour).UTC().Format(time.RFC3339),
		ClusterId:     "c1",
	}
	if !m.IsValid(time.Now(), 5*time.Minute) {
		t.Fatal("token 48h away should be valid")
	}
}

func TestIsValid_WithinGrace_ReturnsFalse(t *testing.T) {
	// 만료까지 1분 남았는데 grace 5분 — false 여야.
	m := &IdentityMaterial{
		IdentityToken: "tok",
		ExpiresAt:     time.Now().Add(1 * time.Minute).UTC().Format(time.RFC3339),
		ClusterId:     "c1",
	}
	if m.IsValid(time.Now(), 5*time.Minute) {
		t.Fatal("token within grace window should be invalid")
	}
}

func TestIsValid_Expired_ReturnsFalse(t *testing.T) {
	m := &IdentityMaterial{
		IdentityToken: "tok",
		ExpiresAt:     time.Now().Add(-1 * time.Hour).UTC().Format(time.RFC3339),
		ClusterId:     "c1",
	}
	if m.IsValid(time.Now(), 0) {
		t.Fatal("expired token should be invalid")
	}
}

func TestIsValid_NilMaterial_ReturnsFalse(t *testing.T) {
	var m *IdentityMaterial
	if m.IsValid(time.Now(), 0) {
		t.Fatal("nil material should be invalid")
	}
}

func TestIsValid_EmptyToken_ReturnsFalse(t *testing.T) {
	m := &IdentityMaterial{IdentityToken: "", ExpiresAt: time.Now().Add(time.Hour).UTC().Format(time.RFC3339)}
	if m.IsValid(time.Now(), 0) {
		t.Fatal("empty token should be invalid")
	}
}

func TestIsValid_BadExpiresAt_ReturnsFalse(t *testing.T) {
	m := &IdentityMaterial{IdentityToken: "tok", ExpiresAt: "not-a-date"}
	if m.IsValid(time.Now(), 0) {
		t.Fatal("unparseable expires_at should be invalid")
	}
}

// ---- K8sSecretIdentityStore — fake clientset ----

func TestK8sSecret_LoadMissing_ReturnsNilNil(t *testing.T) {
	cs := fake.NewSimpleClientset()
	store := NewK8sSecretIdentityStore(cs, "test-ns", "test-id")
	m, err := store.Load(context.Background())
	if err != nil {
		t.Fatalf("Load on missing secret should not error: %v", err)
	}
	if m != nil {
		t.Fatalf("expected nil material, got %+v", m)
	}
}

func TestK8sSecret_SaveCreate(t *testing.T) {
	cs := fake.NewSimpleClientset()
	store := NewK8sSecretIdentityStore(cs, "test-ns", "test-id")
	m := &IdentityMaterial{
		IdentityToken: "opaque-token-xyz",
		ExpiresAt:     "2026-07-19T00:00:00Z",
		ClusterId:     "cluster-uid-1",
	}
	if err := store.Save(context.Background(), m); err != nil {
		t.Fatalf("Save: %v", err)
	}
	// fake 에서 직접 Get 으로 검증.
	sec, err := cs.CoreV1().Secrets("test-ns").Get(context.Background(), "test-id", metav1.GetOptions{})
	if err != nil {
		t.Fatalf("Get after Save: %v", err)
	}
	if string(sec.Data[secretKeyIdentityToken]) != "opaque-token-xyz" {
		t.Errorf("identity_token = %q", sec.Data[secretKeyIdentityToken])
	}
	if string(sec.Data[secretKeyExpiresAt]) != "2026-07-19T00:00:00Z" {
		t.Errorf("expires_at = %q", sec.Data[secretKeyExpiresAt])
	}
	if string(sec.Data[secretKeyClusterID]) != "cluster-uid-1" {
		t.Errorf("cluster_id = %q", sec.Data[secretKeyClusterID])
	}
	if sec.Type != corev1.SecretTypeOpaque {
		t.Errorf("Secret type = %v, want Opaque", sec.Type)
	}
	if sec.Annotations["helm.sh/resource-policy"] != "keep" {
		t.Errorf("missing helm resource-policy keep annotation")
	}
	if sec.Labels["app.kubernetes.io/component"] != "identity" {
		t.Errorf("missing identity component label")
	}
}

func TestK8sSecret_SaveUpdate(t *testing.T) {
	// 기존 Secret 미리 생성 → Save 시 update path.
	existing := &corev1.Secret{
		ObjectMeta: metav1.ObjectMeta{Name: "test-id", Namespace: "test-ns"},
		Type:       corev1.SecretTypeOpaque,
		Data: map[string][]byte{
			secretKeyIdentityToken: []byte("old-token"),
			secretKeyExpiresAt:     []byte("2026-01-01T00:00:00Z"),
			secretKeyClusterID:     []byte("c1"),
		},
	}
	cs := fake.NewSimpleClientset(existing)
	store := NewK8sSecretIdentityStore(cs, "test-ns", "test-id")
	newMat := &IdentityMaterial{
		IdentityToken: "new-token",
		ExpiresAt:     "2026-07-19T00:00:00Z",
		ClusterId:     "c1",
	}
	if err := store.Save(context.Background(), newMat); err != nil {
		t.Fatalf("Save (update): %v", err)
	}
	sec, _ := cs.CoreV1().Secrets("test-ns").Get(context.Background(), "test-id", metav1.GetOptions{})
	if string(sec.Data[secretKeyIdentityToken]) != "new-token" {
		t.Errorf("identity_token after update = %q, want new-token", sec.Data[secretKeyIdentityToken])
	}
}

func TestK8sSecret_RoundTrip(t *testing.T) {
	cs := fake.NewSimpleClientset()
	store := NewK8sSecretIdentityStore(cs, "test-ns", "test-id")
	in := &IdentityMaterial{
		IdentityToken: "tok-rt",
		ExpiresAt:     "2026-07-19T00:00:00Z",
		ClusterId:     "c-rt",
	}
	if err := store.Save(context.Background(), in); err != nil {
		t.Fatalf("Save: %v", err)
	}
	out, err := store.Load(context.Background())
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if out == nil {
		t.Fatal("Load returned nil after Save")
	}
	if *out != *in {
		t.Errorf("round-trip mismatch: in=%+v out=%+v", *in, *out)
	}
}

func TestK8sSecret_Save_RefusesEmpty(t *testing.T) {
	cs := fake.NewSimpleClientset()
	store := NewK8sSecretIdentityStore(cs, "test-ns", "test-id")
	if err := store.Save(context.Background(), &IdentityMaterial{}); err == nil {
		t.Fatal("expected error saving empty material")
	}
}

func TestK8sSecret_DefaultsApplied(t *testing.T) {
	cs := fake.NewSimpleClientset()
	store := NewK8sSecretIdentityStore(cs, "", "")
	if store.namespace != defaultIdentityNamespace {
		t.Errorf("namespace default = %q, want %q", store.namespace, defaultIdentityNamespace)
	}
	if store.secretName != defaultIdentitySecretName {
		t.Errorf("secretName default = %q, want %q", store.secretName, defaultIdentitySecretName)
	}
}

// ---- InMemoryIdentityStore ----

func TestInMemoryStore_RoundTrip(t *testing.T) {
	store := &InMemoryIdentityStore{}
	if m, err := store.Load(context.Background()); err != nil || m != nil {
		t.Fatalf("initial Load = (%+v, %v), want (nil, nil)", m, err)
	}
	in := &IdentityMaterial{IdentityToken: "t", ExpiresAt: "2026-07-19T00:00:00Z", ClusterId: "c"}
	if err := store.Save(context.Background(), in); err != nil {
		t.Fatalf("Save: %v", err)
	}
	out, _ := store.Load(context.Background())
	if out == nil || *out != *in {
		t.Errorf("round-trip mismatch")
	}
}
