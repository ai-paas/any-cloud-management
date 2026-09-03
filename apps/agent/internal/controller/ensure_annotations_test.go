// ENSURE_AGENT_CONFIG_ANNOTATIONS handler tests — backend 가 startup 시 legacy ConfigMap 의
// helm.sh/resource-policy=keep annotation 을 멱등적으로 backfill 호출하는 경로.
package controller

import (
	"context"
	"sync/atomic"
	"testing"

	"anycloud/agent/internal/config"
	agentv1 "anycloud/agent/internal/gen/agent/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/kubernetes/fake"
	clienttesting "k8s.io/client-go/testing"
)

// existingAllowlistCMNoAnnotation — legacy ConfigMap (annotation 없음). HHH 의 §4 backfill 대상.
func existingAllowlistCMNoAnnotation() *corev1.ConfigMap {
	return &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{
			Name:            "aipaas-agent-allowlist",
			Namespace:       "aipaas-system",
			ResourceVersion: "1",
			// Annotations 자체가 nil — handler 가 map 을 새로 만들어야 함.
		},
		Data: map[string]string{
			"allowed_commands": "- \"LIST_PODS\"\n",
		},
	}
}

// existingAllowlistCMWithKeep — 이미 annotation 이 적용된 ConfigMap. handler 가 Update 를 호출하면
// 안 됨.
func existingAllowlistCMWithKeep() *corev1.ConfigMap {
	return &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{
			Name:            "aipaas-agent-allowlist",
			Namespace:       "aipaas-system",
			ResourceVersion: "7",
			Annotations: map[string]string{
				"helm.sh/resource-policy": "keep",
				"other.io/preserve-me":    "true",
			},
		},
		Data: map[string]string{
			"allowed_commands": "- \"LIST_PODS\"\n",
		},
	}
}

// existingAllowlistCMOtherAnnotations — 다른 annotation 이 있지만 helm.sh/resource-policy 는 없음.
// handler 가 기존 annotation 을 보존하면서 keep 만 추가해야 함.
func existingAllowlistCMOtherAnnotations() *corev1.ConfigMap {
	return &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{
			Name:            "aipaas-agent-allowlist",
			Namespace:       "aipaas-system",
			ResourceVersion: "3",
			Annotations: map[string]string{
				"meta.aipaas.io/owner":  "platform",
				"kubectl.kubernetes.io/last-applied-configuration": "{}",
			},
		},
		Data: map[string]string{
			"allowed_commands": "- \"LIST_PODS\"\n",
		},
	}
}

// ensureAnnotationsDispatcher — ENSURE_AGENT_CONFIG_ANNOTATIONS 가 허용된 dispatcher + fake CS.
// applyConfigDispatcher 와 동일 패턴 — loader 는 별도 fake CS (의도적 분리).
func ensureAnnotationsDispatcher(t *testing.T, cs *fake.Clientset, loader *config.Loader) *Dispatcher {
	t.Helper()
	mock := &mockK8sClient{clientset: cs}
	return New("instance-ensure", "", mock, nil, loader)
}

// ─────────────────────────────────────────────────────────────────────────────
// AddsWhenMissing — annotation 없는 ConfigMap → handler 가 추가
// ─────────────────────────────────────────────────────────────────────────────

func TestHandle_EnsureAnnotations_AddsWhenMissing(t *testing.T) {
	cs := fake.NewSimpleClientset(existingAllowlistCMNoAnnotation())
	d := ensureAnnotationsDispatcher(t, cs, permissiveLoader(t))

	resp := d.Handle(&agentv1.CommandRequest{
		Type: agentv1.CommandType_ENSURE_AGENT_CONFIG_ANNOTATIONS,
	})
	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s (code=%s)", resp.GetStatus(), resp.GetErrorMessage(), resp.GetErrorCode())
	}
	fields := resp.GetResult().GetFields()
	if !fields["ensured"].GetBoolValue() {
		t.Errorf("ensured = false, want true")
	}
	if fields["already_present"].GetBoolValue() {
		t.Errorf("already_present = true, want false (annotation was missing)")
	}
	if rv := fields["resource_version"].GetStringValue(); rv == "" {
		t.Errorf("resource_version empty — fake CS should bump it after Update")
	}

	// 실제 ConfigMap 에 annotation 이 박혔는지 확인.
	updated, err := cs.CoreV1().ConfigMaps("aipaas-system").Get(context.Background(),
		"aipaas-agent-allowlist", metav1.GetOptions{})
	if err != nil {
		t.Fatalf("get updated cm: %v", err)
	}
	if got := updated.Annotations["helm.sh/resource-policy"]; got != "keep" {
		t.Errorf("annotation helm.sh/resource-policy = %q, want %q", got, "keep")
	}
	// data 는 손대지 않았는지 확인 — 핵심 invariant.
	if updated.Data["allowed_commands"] != "- \"LIST_PODS\"\n" {
		t.Errorf("data was modified, want untouched: %q", updated.Data["allowed_commands"])
	}
}

// ─────────────────────────────────────────────────────────────────────────────
// NoOpWhenAlreadyPresent — 이미 annotation 이 있으면 Update API 호출 자체가 없어야 함 (멱등성)
// ─────────────────────────────────────────────────────────────────────────────

func TestHandle_EnsureAnnotations_NoOpWhenAlreadyPresent(t *testing.T) {
	cs := fake.NewSimpleClientset(existingAllowlistCMWithKeep())

	// Update reactor 가 호출되면 counter 증가 — no-op 검증용. PrependReactor 는 chain 의 앞에
	// 끼어들어 호출을 count 한 뒤 false 반환해서 기본 동작에 위임 (true 반환하면 결과를 가로챔).
	var updateCalls atomic.Int32
	cs.PrependReactor("update", "configmaps", func(action clienttesting.Action) (bool, runtime.Object, error) {
		updateCalls.Add(1)
		return false, nil, nil // delegate to next reactor (default fake handler).
	})

	d := ensureAnnotationsDispatcher(t, cs, permissiveLoader(t))
	resp := d.Handle(&agentv1.CommandRequest{
		Type: agentv1.CommandType_ENSURE_AGENT_CONFIG_ANNOTATIONS,
	})
	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	fields := resp.GetResult().GetFields()
	if !fields["already_present"].GetBoolValue() {
		t.Errorf("already_present = false, want true")
	}
	if rv := fields["resource_version"].GetStringValue(); rv != "7" {
		t.Errorf("resource_version = %q, want %q (no Update so RV unchanged)", rv, "7")
	}
	if n := updateCalls.Load(); n != 0 {
		t.Errorf("Update API called %d time(s), want 0 (idempotent no-op)", n)
	}
}

// ─────────────────────────────────────────────────────────────────────────────
// PreservesOtherAnnotations — 기존 annotation 보존 + keep 만 추가
// ─────────────────────────────────────────────────────────────────────────────

func TestHandle_EnsureAnnotations_PreservesOtherAnnotations(t *testing.T) {
	cs := fake.NewSimpleClientset(existingAllowlistCMOtherAnnotations())
	d := ensureAnnotationsDispatcher(t, cs, permissiveLoader(t))

	resp := d.Handle(&agentv1.CommandRequest{
		Type: agentv1.CommandType_ENSURE_AGENT_CONFIG_ANNOTATIONS,
	})
	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	if resp.GetResult().GetFields()["already_present"].GetBoolValue() {
		t.Errorf("already_present = true, want false")
	}

	updated, err := cs.CoreV1().ConfigMaps("aipaas-system").Get(context.Background(),
		"aipaas-agent-allowlist", metav1.GetOptions{})
	if err != nil {
		t.Fatalf("get updated cm: %v", err)
	}
	if got := updated.Annotations["helm.sh/resource-policy"]; got != "keep" {
		t.Errorf("annotation helm.sh/resource-policy = %q, want %q", got, "keep")
	}
	if got := updated.Annotations["meta.aipaas.io/owner"]; got != "platform" {
		t.Errorf("existing annotation meta.aipaas.io/owner = %q, want %q (must preserve)",
			got, "platform")
	}
	if _, ok := updated.Annotations["kubectl.kubernetes.io/last-applied-configuration"]; !ok {
		t.Errorf("existing kubectl annotation lost — must preserve")
	}
}

// ─────────────────────────────────────────────────────────────────────────────
// NotInAllowlist_PermissionDenied — allowlist 미포함 시 진입 자체 차단
// ─────────────────────────────────────────────────────────────────────────────

func TestHandle_EnsureAnnotations_NotInAllowlist_PermissionDenied(t *testing.T) {
	cs := fake.NewSimpleClientset(existingAllowlistCMNoAnnotation())
	d := ensureAnnotationsDispatcher(t, cs, denyAllLoader(t))

	resp := d.Handle(&agentv1.CommandRequest{
		Type: agentv1.CommandType_ENSURE_AGENT_CONFIG_ANNOTATIONS,
	})
	if resp.GetStatus() != agentv1.Status_PERMISSION_DENIED {
		t.Errorf("status = %v, want PERMISSION_DENIED", resp.GetStatus())
	}
	if resp.GetErrorCode() != "COMMAND_NOT_ALLOWED" {
		t.Errorf("error_code = %q, want COMMAND_NOT_ALLOWED", resp.GetErrorCode())
	}
}

// ─────────────────────────────────────────────────────────────────────────────
// NotFound_ReturnsConfigMapNotFound — ConfigMap 자체가 없음
// ─────────────────────────────────────────────────────────────────────────────

func TestHandle_EnsureAnnotations_NotFound_ReturnsConfigMapNotFound(t *testing.T) {
	cs := fake.NewSimpleClientset() // 빈 CS — ConfigMap 자체가 없음.
	d := ensureAnnotationsDispatcher(t, cs, permissiveLoader(t))

	resp := d.Handle(&agentv1.CommandRequest{
		Type: agentv1.CommandType_ENSURE_AGENT_CONFIG_ANNOTATIONS,
	})
	if resp.GetStatus() != agentv1.Status_FAILED {
		t.Errorf("status = %v, want FAILED", resp.GetStatus())
	}
	if resp.GetErrorCode() != "CONFIGMAP_NOT_FOUND" {
		t.Errorf("error_code = %q, want CONFIGMAP_NOT_FOUND", resp.GetErrorCode())
	}
}
