// 60일짜리 identity_token 을 K8s Secret 에 보관한다. 메모리에만 두면 pod 가 재시작할 때마다
// 다시 등록해야 한다.
//
// Secret 의 키 이름이 계약이다. data 에 identity_token, expires_at, cluster_id 가 들어간다.
// metadata 에는 helm.sh/resource-policy: keep 을 붙여 helm upgrade 가 지우지 못하게 한다.
//
// Load 와 Save 는 상태를 들고 있지 않다. 같은 프로세스에서 bootstrap 과 rotation goroutine 이
// 함께 불러도 K8s 의 resourceVersion 이 순서를 잡아 준다.

package core

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
)

const (
	defaultIdentitySecretName = "cluster-agent-identity"
	defaultIdentityNamespace  = "aipaas-system"

	secretKeyIdentityToken = "identity_token"
	secretKeyExpiresAt     = "expires_at"
	secretKeyClusterID     = "cluster_id"
)

// IdentityMaterial — identity_token + 메타. agent 의 영구 인증 자격 — 재시작 후에도 같은
// token 으로 AgentRuntime.Stream 인증 가능. 만료 임박 시 RotateIdentityToken RPC 로 갱신 →
// 다시 Save.
type IdentityMaterial struct {
	IdentityToken string
	ExpiresAt     string // RFC3339
	ClusterId     string
}

// IsZero — token 미존재 (legacy / first boot) 여부.
func (m *IdentityMaterial) IsZero() bool {
	return m == nil || m.IdentityToken == ""
}

// IsValid — 현재 시각 기준 token 사용 가능 여부. graceMin 이내로 만료 임박이면 false —
// caller 가 미리 새 token 발급받도록 유도. ExpiresAt 파싱 실패 시에도 false (보수적 — 알 수 없는
// 상태는 re-register 로 유도).
func (m *IdentityMaterial) IsValid(now time.Time, graceMin time.Duration) bool {
	if m.IsZero() {
		return false
	}
	exp, err := time.Parse(time.RFC3339, m.ExpiresAt)
	if err != nil {
		return false
	}
	if graceMin < 0 {
		graceMin = 0
	}
	return now.Add(graceMin).Before(exp)
}

// IdentityStore — load / save / delete abstraction. tests 는 InMemoryIdentityStore 사용,
// production 은 K8sSecretIdentityStore.
type IdentityStore interface {
	// Load — 기존 저장된 identity material 반환. Secret 미존재 → (nil, nil) — caller 가 첫
	// Register 진행.
	Load(ctx context.Context) (*IdentityMaterial, error)

	// Save — bootstrap / rotation 직후 호출. namespace 의 Secret 을 create 또는 update.
	Save(ctx context.Context, m *IdentityMaterial) error

	// Delete — backend 가 token 을 invalidate 한 상태로 판단될 때 (runtime stream Unauthenticated
	// 반복) self-heal 호출. Secret 삭제 후 pod 가 재시작되면 REGISTRATION_TOKEN env 로 fresh register.
	// Secret 미존재여도 에러 X (idempotent).
	Delete(ctx context.Context) error
}

// ============================================================================
// K8s Secret 구현 — production path.
// ============================================================================

// K8sSecretIdentityStore — namespace 의 Opaque Secret 1개에 identity_token 영구 보관.
//
// 권한 요구: agent ServiceAccount 가 본 namespace 의 secrets 에 get/create/update/patch.
// Helm chart 의 RBAC 가 resourceNames 로 단일 Secret 만 허용 (security narrow scope).
type K8sSecretIdentityStore struct {
	cs         kubernetes.Interface
	namespace  string
	secretName string
}

// NewK8sSecretIdentityStore — namespace / secretName 빈 문자열이면 default
// ("aipaas-system" / "cluster-agent-identity") 사용.
func NewK8sSecretIdentityStore(cs kubernetes.Interface, namespace, secretName string) *K8sSecretIdentityStore {
	if namespace == "" {
		namespace = defaultIdentityNamespace
	}
	if secretName == "" {
		secretName = defaultIdentitySecretName
	}
	return &K8sSecretIdentityStore{cs: cs, namespace: namespace, secretName: secretName}
}

// Load — Secret 의 3개 키를 IdentityMaterial 로. Secret 미존재 → (nil, nil) — caller 가
// 첫 Register 진행. 존재해도 identity_token 비어 있으면 nil 처리 (corrupt 보호).
func (s *K8sSecretIdentityStore) Load(ctx context.Context) (*IdentityMaterial, error) {
	if s.cs == nil {
		return nil, errors.New("k8s clientset not initialized")
	}
	sec, err := s.cs.CoreV1().Secrets(s.namespace).Get(ctx, s.secretName, metav1.GetOptions{})
	if err != nil {
		if apierrors.IsNotFound(err) {
			return nil, nil // first boot
		}
		return nil, fmt.Errorf("get identity secret %s/%s: %w", s.namespace, s.secretName, err)
	}
	m := &IdentityMaterial{
		IdentityToken: string(sec.Data[secretKeyIdentityToken]),
		ExpiresAt:     string(sec.Data[secretKeyExpiresAt]),
		ClusterId:     string(sec.Data[secretKeyClusterID]),
	}
	if m.IsZero() {
		slog.Warn("identity secret found but token empty — treating as missing",
			slog.String("secret", s.namespace+"/"+s.secretName))
		return nil, nil
	}
	return m, nil
}

// Save — bootstrap / rotation 직후 호출. namespace 의 Secret create 또는 update.
//
// Labels: app.kubernetes.io/{name,component,managed-by}
// Annotations:
//   - helm.sh/resource-policy=keep — Helm upgrade/uninstall 시 Secret 보존 (재시작 안정성 확보)
//   - aipaas.io/cluster-id, aipaas.io/expires-at — debug/audit
//
// 동시성 노트: 단일 agent process (또는 leader pod) 만 Save 호출. 외부 (operator manual
// kubectl) 와의 race 는 K8s API 의 optimistic concurrency 에 의존 — conflict 면 다음 rotation
// 또는 startup 에서 다시 시도.
func (s *K8sSecretIdentityStore) Save(ctx context.Context, m *IdentityMaterial) error {
	if s.cs == nil {
		return errors.New("k8s clientset not initialized")
	}
	if m.IsZero() {
		return errors.New("refusing to save empty identity material")
	}
	data := map[string][]byte{
		secretKeyIdentityToken: []byte(m.IdentityToken),
		secretKeyExpiresAt:     []byte(m.ExpiresAt),
		secretKeyClusterID:     []byte(m.ClusterId),
	}
	desired := &corev1.Secret{
		ObjectMeta: metav1.ObjectMeta{
			Name:      s.secretName,
			Namespace: s.namespace,
			Labels: map[string]string{
				"app.kubernetes.io/name":       "cluster-agent",
				"app.kubernetes.io/component":  "identity",
				"app.kubernetes.io/managed-by": "cluster-agent",
			},
			Annotations: map[string]string{
				"helm.sh/resource-policy": "keep",
				"aipaas.io/cluster-id":    m.ClusterId,
				"aipaas.io/expires-at":    m.ExpiresAt,
			},
		},
		Type: corev1.SecretTypeOpaque,
		Data: data,
	}

	_, err := s.cs.CoreV1().Secrets(s.namespace).Get(ctx, s.secretName, metav1.GetOptions{})
	if apierrors.IsNotFound(err) {
		_, err = s.cs.CoreV1().Secrets(s.namespace).Create(ctx, desired, metav1.CreateOptions{})
		if err != nil {
			return fmt.Errorf("create identity secret: %w", err)
		}
		slog.Info("identity token saved (created)",
			slog.String("secret", s.namespace+"/"+s.secretName),
			slog.String("cluster_id", m.ClusterId),
			slog.String("expires_at", m.ExpiresAt))
		return nil
	}
	if err != nil {
		return fmt.Errorf("pre-update get identity secret: %w", err)
	}
	_, err = s.cs.CoreV1().Secrets(s.namespace).Update(ctx, desired, metav1.UpdateOptions{})
	if err != nil {
		return fmt.Errorf("update identity secret: %w", err)
	}
	slog.Info("identity token saved (updated)",
		slog.String("secret", s.namespace+"/"+s.secretName),
		slog.String("cluster_id", m.ClusterId),
		slog.String("expires_at", m.ExpiresAt))
	return nil
}

// Delete — Secret 제거. NotFound 는 success 로 swallow (idempotent).
func (s *K8sSecretIdentityStore) Delete(ctx context.Context) error {
	err := s.cs.CoreV1().Secrets(s.namespace).Delete(ctx, s.secretName, metav1.DeleteOptions{})
	if err != nil && !apierrors.IsNotFound(err) {
		return fmt.Errorf("delete identity secret: %w", err)
	}
	slog.Warn("identity token deleted — pod restart will re-bootstrap",
		slog.String("secret", s.namespace+"/"+s.secretName))
	return nil
}

// ============================================================================
// In-memory 구현 — tests / dev (k8s 미접속) 용.
// ============================================================================

// InMemoryIdentityStore — process-local 보관. mutex 로 동시 Save/Load race-free.
type InMemoryIdentityStore struct {
	mu       sync.RWMutex
	material *IdentityMaterial
}

func (s *InMemoryIdentityStore) Load(ctx context.Context) (*IdentityMaterial, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.material == nil {
		return nil, nil
	}
	cp := *s.material
	return &cp, nil
}

func (s *InMemoryIdentityStore) Save(ctx context.Context, m *IdentityMaterial) error {
	if m.IsZero() {
		return errors.New("refusing to save empty identity material")
	}
	cp := *m
	s.mu.Lock()
	s.material = &cp
	s.mu.Unlock()
	return nil
}

func (s *InMemoryIdentityStore) Delete(ctx context.Context) error {
	s.mu.Lock()
	s.material = nil
	s.mu.Unlock()
	return nil
}
