// APPLY_AGENT_CONFIG handler tests — backend 가 PUT /v1/admin/clusters/{c}/agent-policy 받아
// agent 에게 snapshot push 했을 때 agent 가 ConfigMap 을 갱신하는지 검증.
package controller

import (
	"context"
	"strings"
	"testing"

	"anycloud/agent/internal/config"
	agentv1 "anycloud/agent/internal/gen/agent/v1"
	corev1 "k8s.io/api/core/v1"
	k8serrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/kubernetes/fake"
	clienttesting "k8s.io/client-go/testing"

	"google.golang.org/protobuf/types/known/structpb"
)

// existingAllowlistCM — 테스트가 시작점으로 사용하는 ConfigMap. test 가 dispatch 후 data 가
// 갱신되었는지 비교.
func existingAllowlistCM() *corev1.ConfigMap {
	return &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{
			Name:            "aipaas-agent-allowlist",
			Namespace:       "aipaas-system",
			ResourceVersion: "1",
		},
		Data: map[string]string{
			"allowed_namespaces": "- \"old-ns\"\n",
			"allowed_commands":   "- \"LIST_PODS\"\n",
		},
	}
}

// applyConfigParams — 테스트 helper. 모든 5 개 param 을 한 번에 string struct 로 packing.
//
// 빈 문자열은 backend 가 omit 한 것과 동일하게 동작 — getStringParam 이 빈 문자열 반환.
func applyConfigParams(t *testing.T, namespaces, commands, charts, execNs, resourcePolicy string) *structpb.Struct {
	t.Helper()
	fields := map[string]interface{}{}
	if namespaces != "" {
		fields["allowed_namespaces"] = namespaces
	}
	if commands != "" {
		fields["allowed_commands"] = commands
	}
	if charts != "" {
		fields["allowed_charts"] = charts
	}
	if execNs != "" {
		fields["allowed_exec_namespaces"] = execNs
	}
	if resourcePolicy != "" {
		fields["resource_policy"] = resourcePolicy
	}
	s, err := structpb.NewStruct(fields)
	if err != nil {
		t.Fatalf("structpb.NewStruct: %v", err)
	}
	return s
}

// applyConfigDispatcher — APPLY_AGENT_CONFIG 가 허용된 dispatcher + fake clientset.
// loader 는 permissiveLoader (APPLY_AGENT_CONFIG 포함) — 단, ConfigMap operand 는 별도로 fake CS 에
// 주입. loader 의 watch 와 dispatcher 의 update 가 같은 fake CS 를 공유하면 race 위험이 있어
// 의도적으로 분리 (테스트는 dispatcher 의 update 동작만 검증, watch 자체는 allowlist_test 에서 cover).
func applyConfigDispatcher(t *testing.T, cs *fake.Clientset, loader *config.Loader) *Dispatcher {
	t.Helper()
	mock := &mockK8sClient{clientset: cs}
	return New("instance-apply", "", mock, nil, loader)
}

// loaderWithApplyAllowed — allowlist 에 APPLY_AGENT_CONFIG 포함. configmap 자체는 별도 namespace
// 에 두지 않고 inline (LoadOnce 가 not-found 면 deny-all 이지만 test 는 inline ConfigMap 으로 정상 load).
func loaderWithApplyAllowed(t *testing.T) *config.Loader {
	t.Helper()
	return permissiveLoader(t)
}

// ─────────────────────────────────────────────────────────────────────────────
// Happy path
// ─────────────────────────────────────────────────────────────────────────────

func TestHandle_ApplyAgentConfig_Success(t *testing.T) {
	// fake CS 에 미리 ConfigMap 주입.
	cs := fake.NewSimpleClientset(existingAllowlistCM())
	d := applyConfigDispatcher(t, cs, loaderWithApplyAllowed(t))

	params := applyConfigParams(t,
		`["monitoring","app"]`,
		`["LIST_PODS","GET_LOG","APPLY_AGENT_CONFIG"]`,
		`["prometheus-community/kube-prometheus-stack:45.0.0-50.0.0"]`,
		`["default"]`,
		"mode: allow_all_discovered\ndeny:\n  - kind: secrets\n",
	)
	resp := d.Handle(&agentv1.CommandRequest{
		Type:   agentv1.CommandType_APPLY_AGENT_CONFIG,
		Params: params,
	})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s (code=%s)", resp.GetStatus(), resp.GetErrorMessage(), resp.GetErrorCode())
	}
	fields := resp.GetResult().GetFields()
	if !fields["applied"].GetBoolValue() {
		t.Errorf("applied = false, want true")
	}
	rv := fields["resource_version"].GetStringValue()
	if rv == "" {
		t.Errorf("resource_version empty — fake CS should bump it")
	}

	// ConfigMap 의 새 data 확인 — yamlList 가 각 entry 를 `- "value"\n` 형식으로 정확히 직렬화하는지.
	updated, err := cs.CoreV1().ConfigMaps("aipaas-system").Get(context.Background(),
		"aipaas-agent-allowlist", metav1.GetOptions{})
	if err != nil {
		t.Fatalf("get updated cm: %v", err)
	}
	if !strings.Contains(updated.Data["allowed_namespaces"], `"monitoring"`) ||
		!strings.Contains(updated.Data["allowed_namespaces"], `"app"`) {
		t.Errorf("allowed_namespaces = %q", updated.Data["allowed_namespaces"])
	}
	if !strings.Contains(updated.Data["allowed_commands"], `"LIST_PODS"`) ||
		!strings.Contains(updated.Data["allowed_commands"], `"APPLY_AGENT_CONFIG"`) {
		t.Errorf("allowed_commands = %q", updated.Data["allowed_commands"])
	}
	if !strings.Contains(updated.Data["allowed_charts"],
		`"prometheus-community/kube-prometheus-stack:45.0.0-50.0.0"`) {
		t.Errorf("allowed_charts = %q", updated.Data["allowed_charts"])
	}
	if !strings.Contains(updated.Data["allowed_exec_namespaces"], `"default"`) {
		t.Errorf("allowed_exec_namespaces = %q", updated.Data["allowed_exec_namespaces"])
	}
	if !strings.Contains(updated.Data["resource_policy"], "allow_all_discovered") {
		t.Errorf("resource_policy = %q", updated.Data["resource_policy"])
	}

	// 기존 data 가 완전 교체되었는지 (old-ns 가 사라졌는지) — partial update 가 아니라 full replace.
	if strings.Contains(updated.Data["allowed_namespaces"], "old-ns") {
		t.Errorf("expected full data replacement, but old-ns still present: %q",
			updated.Data["allowed_namespaces"])
	}
}

// ─────────────────────────────────────────────────────────────────────────────
// helm_repositories JSON param 처리.
//
// backend 의 APPLY_AGENT_CONFIG 가 helm_repositories JSON array 같이 보낼 때:
//   1) ConfigMap data 의 helm_repositories key 가 정확히 raw JSON 값으로 set
//   2) 응답에 helm_repos_synced / helm_repos_removed 카운트 노출 (helm client nil 이면 0)
//   3) malformed JSON 은 silent — sync 만 fail, ConfigMap update 는 진행
// ─────────────────────────────────────────────────────────────────────────────

func TestHandle_ApplyAgentConfig_HelmRepositories_StoredAsRaw(t *testing.T) {
	cs := fake.NewSimpleClientset(existingAllowlistCM())
	d := applyConfigDispatcher(t, cs, loaderWithApplyAllowed(t))

	// 정상 JSON array — 3 entries.
	helmRaw := `[{"name":"anycloud-prom","url":"https://prom.example.com","insecure_skip_tls_verify":false},` +
		`{"name":"anycloud-grafana","url":"https://grafana.example.com"},` +
		`{"name":"user-extra","url":"https://user.example.com","username":"u","password":"p"}]`

	fields := map[string]interface{}{
		"allowed_namespaces": `["*"]`,
		"allowed_commands":   `["LIST_PODS","APPLY_AGENT_CONFIG"]`,
		"allowed_charts":     `["*/*:0.0.0-99.99.99"]`,
		"helm_repositories":  helmRaw,
	}
	params, err := structpb.NewStruct(fields)
	if err != nil {
		t.Fatalf("structpb.NewStruct: %v", err)
	}
	resp := d.Handle(&agentv1.CommandRequest{
		Type:   agentv1.CommandType_APPLY_AGENT_CONFIG,
		Params: params,
	})

	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s (code=%s)", resp.GetStatus(), resp.GetErrorMessage(), resp.GetErrorCode())
	}

	// 1) ConfigMap 의 helm_repositories key 가 raw JSON 으로 정확히 저장됐는지
	updated, err := cs.CoreV1().ConfigMaps("aipaas-system").Get(context.Background(),
		"aipaas-agent-allowlist", metav1.GetOptions{})
	if err != nil {
		t.Fatalf("get updated cm: %v", err)
	}
	gotRaw := updated.Data["helm_repositories"]
	if gotRaw != helmRaw {
		t.Errorf("helm_repositories raw mismatch\nwant: %s\n got: %s", helmRaw, gotRaw)
	}

	// 2) 응답 필드 — helm client nil 이라 synced=0 / removed=0 / no error.
	out := resp.GetResult().GetFields()
	if got := out["helm_repos_synced"].GetNumberValue(); got != 0 {
		t.Errorf("helm_repos_synced = %v, want 0 (helm client nil)", got)
	}
	if got := out["helm_repos_removed"].GetNumberValue(); got != 0 {
		t.Errorf("helm_repos_removed = %v, want 0", got)
	}
	if got := out["helm_sync_error"].GetStringValue(); got != "" {
		t.Errorf("helm_sync_error = %q, want empty (nil client = no-op)", got)
	}
}

func TestHandle_ApplyAgentConfig_HelmRepositories_Empty(t *testing.T) {
	cs := fake.NewSimpleClientset(existingAllowlistCM())
	d := applyConfigDispatcher(t, cs, loaderWithApplyAllowed(t))

	// helm_repositories param 미지정 — ConfigMap 의 key 는 empty string 으로 저장 (delete intent).
	params := applyConfigParams(t, `["*"]`, `["LIST_PODS"]`, "", "", "")
	resp := d.Handle(&agentv1.CommandRequest{
		Type:   agentv1.CommandType_APPLY_AGENT_CONFIG,
		Params: params,
	})
	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	updated, err := cs.CoreV1().ConfigMaps("aipaas-system").Get(context.Background(),
		"aipaas-agent-allowlist", metav1.GetOptions{})
	if err != nil {
		t.Fatalf("get cm: %v", err)
	}
	if got := updated.Data["helm_repositories"]; got != "" {
		t.Errorf("helm_repositories should be empty when param omitted, got %q", got)
	}
}

func TestHandle_ApplyAgentConfig_HelmRepositories_Malformed(t *testing.T) {
	cs := fake.NewSimpleClientset(existingAllowlistCM())
	d := applyConfigDispatcher(t, cs, loaderWithApplyAllowed(t))

	// malformed JSON — parser 가 error 반환. 단 ConfigMap update 는 그대로 진행.
	malformed := `{not a json array}`
	fields := map[string]interface{}{
		"allowed_namespaces": `["*"]`,
		"allowed_commands":   `["LIST_PODS","APPLY_AGENT_CONFIG"]`,
		"allowed_charts":     `["*/*:0.0.0-99.99.99"]`,
		"helm_repositories":  malformed,
	}
	params, _ := structpb.NewStruct(fields)
	resp := d.Handle(&agentv1.CommandRequest{
		Type:   agentv1.CommandType_APPLY_AGENT_CONFIG,
		Params: params,
	})

	// 응답 자체는 OK — ConfigMap update 성공.
	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}
	// helm_sync_error 필드에 parse 에러 흔적 — helm client 가 nil 이라 silent (skip).
	// helm.Client 가 있을 때만 parse 후 fail → error 노출. 본 테스트는 nil client path.
	out := resp.GetResult().GetFields()
	if got := out["helm_repos_synced"].GetNumberValue(); got != 0 {
		t.Errorf("helm_repos_synced = %v, want 0 (nil client = no-op)", got)
	}

	// ConfigMap 의 helm_repositories key 에는 raw 그대로 저장 — backend 가 다음 push 때 정정.
	updated, _ := cs.CoreV1().ConfigMaps("aipaas-system").Get(context.Background(),
		"aipaas-agent-allowlist", metav1.GetOptions{})
	if got := updated.Data["helm_repositories"]; got != malformed {
		t.Errorf("helm_repositories raw should still be stored (next push will fix); got %q", got)
	}
}

// ─────────────────────────────────────────────────────────────────────────────
// CONFIGMAP_NOT_FOUND
// ─────────────────────────────────────────────────────────────────────────────

func TestHandle_ApplyAgentConfig_ConfigMapNotFound(t *testing.T) {
	// 빈 fake CS — ConfigMap 자체가 없음.
	cs := fake.NewSimpleClientset()
	d := applyConfigDispatcher(t, cs, loaderWithApplyAllowed(t))

	params := applyConfigParams(t, `["x"]`, `["LIST_PODS"]`, "", "", "")
	resp := d.Handle(&agentv1.CommandRequest{
		Type:   agentv1.CommandType_APPLY_AGENT_CONFIG,
		Params: params,
	})

	if resp.GetStatus() != agentv1.Status_FAILED {
		t.Errorf("status = %v, want FAILED", resp.GetStatus())
	}
	if resp.GetErrorCode() != "CONFIGMAP_NOT_FOUND" {
		t.Errorf("error_code = %q, want CONFIGMAP_NOT_FOUND", resp.GetErrorCode())
	}
}

// ─────────────────────────────────────────────────────────────────────────────
// INVALID_PAYLOAD — malformed JSON
// ─────────────────────────────────────────────────────────────────────────────

func TestHandle_ApplyAgentConfig_InvalidJsonPayload(t *testing.T) {
	cs := fake.NewSimpleClientset(existingAllowlistCM())
	d := applyConfigDispatcher(t, cs, loaderWithApplyAllowed(t))

	// allowed_namespaces 가 JSON array 가 아님 — parse 실패 예상.
	params := applyConfigParams(t, `not-an-array`, `["LIST_PODS"]`, "", "", "")
	resp := d.Handle(&agentv1.CommandRequest{
		Type:   agentv1.CommandType_APPLY_AGENT_CONFIG,
		Params: params,
	})

	if resp.GetStatus() != agentv1.Status_INVALID_PARAMS {
		t.Errorf("status = %v, want INVALID_PARAMS", resp.GetStatus())
	}
	if resp.GetErrorCode() != "INVALID_PAYLOAD" {
		t.Errorf("error_code = %q, want INVALID_PAYLOAD", resp.GetErrorCode())
	}
	// 에러 메시지에 어느 key 가 잘못됐는지 포함.
	if !strings.Contains(resp.GetErrorMessage(), "allowed_namespaces") {
		t.Errorf("error_message should reference the bad key: %s", resp.GetErrorMessage())
	}
}

// ─────────────────────────────────────────────────────────────────────────────
// PERMISSION_DENIED — allowlist 에 APPLY_AGENT_CONFIG 없음
// ─────────────────────────────────────────────────────────────────────────────

func TestHandle_ApplyAgentConfig_NotInAllowlist_PermissionDenied(t *testing.T) {
	cs := fake.NewSimpleClientset(existingAllowlistCM())
	// denyAllLoader 는 commands 가 비어있어 모든 명령 deny.
	d := applyConfigDispatcher(t, cs, denyAllLoader(t))

	params := applyConfigParams(t, `["x"]`, `["LIST_PODS"]`, "", "", "")
	resp := d.Handle(&agentv1.CommandRequest{
		Type:   agentv1.CommandType_APPLY_AGENT_CONFIG,
		Params: params,
	})

	if resp.GetStatus() != agentv1.Status_PERMISSION_DENIED {
		t.Errorf("status = %v, want PERMISSION_DENIED", resp.GetStatus())
	}
	if resp.GetErrorCode() != "COMMAND_NOT_ALLOWED" {
		t.Errorf("error_code = %q, want COMMAND_NOT_ALLOWED", resp.GetErrorCode())
	}
}

// ─────────────────────────────────────────────────────────────────────────────
// resource_policy YAML 가 verbatim pass-through 인지 — agent 가 parsing 안 함.
// ─────────────────────────────────────────────────────────────────────────────

func TestHandle_ApplyAgentConfig_ResourcePolicyPassedThrough(t *testing.T) {
	cs := fake.NewSimpleClientset(existingAllowlistCM())
	d := applyConfigDispatcher(t, cs, loaderWithApplyAllowed(t))

	policyYAML := `mode: strict
allow:
  - kind: pods
  - kind: services
    namespace: web
deny:
  - kind: secrets
`
	params := applyConfigParams(t, `["monitoring"]`, `["LIST_PODS"]`, "", "", policyYAML)
	resp := d.Handle(&agentv1.CommandRequest{
		Type:   agentv1.CommandType_APPLY_AGENT_CONFIG,
		Params: params,
	})
	if resp.GetStatus() != agentv1.Status_OK {
		t.Fatalf("status = %v: %s", resp.GetStatus(), resp.GetErrorMessage())
	}

	updated, err := cs.CoreV1().ConfigMaps("aipaas-system").Get(context.Background(),
		"aipaas-agent-allowlist", metav1.GetOptions{})
	if err != nil {
		t.Fatalf("get updated cm: %v", err)
	}
	if updated.Data["resource_policy"] != policyYAML {
		t.Errorf("resource_policy not verbatim:\n got %q\nwant %q",
			updated.Data["resource_policy"], policyYAML)
	}
}

// ─────────────────────────────────────────────────────────────────────────────
// Bonus — Conflict retry 동작 검증 (3 번 연속 Conflict → max retries 초과 시 K8S_API_ERROR).
// 실제 production 에선 동시 update 가 드물지만 retry path 가 죽지 않았는지 sanity check.
// ─────────────────────────────────────────────────────────────────────────────

func TestHandle_ApplyAgentConfig_ConflictExceedsRetries_K8sApiError(t *testing.T) {
	cs := fake.NewSimpleClientset(existingAllowlistCM())
	// reactor 가 Update 호출마다 항상 Conflict 반환 — retry loop 가 멈추는지 검증.
	cs.PrependReactor("update", "configmaps", func(action clienttesting.Action) (bool, runtime.Object, error) {
		return true, nil, k8serrors.NewConflict(
			corev1.Resource("configmaps").WithVersion("v1").GroupResource(),
			"aipaas-agent-allowlist",
			errConflict("resourceVersion mismatch"),
		)
	})
	d := applyConfigDispatcher(t, cs, loaderWithApplyAllowed(t))

	params := applyConfigParams(t, `["x"]`, `["LIST_PODS"]`, "", "", "")
	resp := d.Handle(&agentv1.CommandRequest{
		Type:   agentv1.CommandType_APPLY_AGENT_CONFIG,
		Params: params,
	})

	if resp.GetStatus() != agentv1.Status_FAILED {
		t.Errorf("status = %v, want FAILED", resp.GetStatus())
	}
	if resp.GetErrorCode() != "K8S_API_ERROR" {
		t.Errorf("error_code = %q, want K8S_API_ERROR", resp.GetErrorCode())
	}
	if !strings.Contains(resp.GetErrorMessage(), "too many conflicts") {
		t.Errorf("error_message should mention retries: %s", resp.GetErrorMessage())
	}
}

// errConflict — k8serrors.NewConflict 의 cause error.
type errConflict string

func (e errConflict) Error() string { return string(e) }
