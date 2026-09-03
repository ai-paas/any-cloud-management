// Apply policy snapshot — APPLY_AGENT_CONFIG handler.
//
// Backend 가 PUT /v1/admin/clusters/{c}/agent-policy 받아 agent 에 새 allowlist + resource_policy
// snapshot 을 push. agent 가 자기 ConfigMap (aipaas-agent-allowlist) 을 K8s API 로 update →
// 기존 config.Loader 의 watch 가 자동 reload. agent restart 불요.
//
// 데이터 흐름:
//
//	backend ──(APPLY_AGENT_CONFIG)──▶ dispatcher.applyAgentConfig
//	                                       │
//	                                       ▼
//	                            CoreV1().ConfigMaps(ns).Get(name)
//	                                       │
//	                                       ▼  data 갱신
//	                            CoreV1().ConfigMaps(ns).Update(cm)
//	                                       │  ↺ 충돌 시 3 회 재시도 (Conflict only)
//	                                       ▼
//	                            return {applied: true, resource_version: ...}
//	                                       │
//	  config.Loader.Watch goroutine ◀──────┘  (별도 channel — agent 내부 통지)
//	  → swap(in-memory AllowList)
//
// 에러 분류:
//
//	NotFound          → status=FAILED          + error_code=CONFIGMAP_NOT_FOUND
//	Forbidden (RBAC)  → status=FAILED          + error_code=FORBIDDEN
//	JSON parse 실패    → status=INVALID_PARAMS  + error_code=INVALID_PAYLOAD
//	기타 K8s API      → status=FAILED          + error_code=K8S_API_ERROR
//
// Retry 정책: ResourceVersion mismatch (Conflict) 만 재시도 — 다른 누군가가 동시 update 시 latest
// 를 다시 받아 merge. NotFound / Forbidden / Invalid 는 재시도 무의미.

package controller

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"strings"

	agentv1 "anycloud/agent/internal/gen/agent/v1"
	"anycloud/agent/internal/helm"
	"google.golang.org/protobuf/types/known/structpb"
	k8serrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// applyConfigMaxRetries — Update 가 Conflict 로 실패할 때 재시도 횟수. ConfigMap 은 거의 동시
// update 가 발생하지 않으므로 3 회면 충분 (operator 가 kubectl edit 동시 수행 같은 희귀 케이스).
const applyConfigMaxRetries = 3

// allowlistConfigMapTarget — handler 가 patch 할 ConfigMap 위치.
type allowlistConfigMapTarget struct {
	namespace string
	name      string
}

// resolveAllowlistTarget — env var 우선, 없으면 main.go 의 initAllowList 와 동일한 default.
//
//   - AGENT_NAMESPACE         ─ ConfigMap namespace      (default "aipaas-system")
//   - ALLOWLIST_CONFIGMAP     ─ ConfigMap name           (default "aipaas-agent-allowlist")
//
// 별칭 (AIPAAS_ALLOWLIST_NAMESPACE / AIPAAS_ALLOWLIST_CONFIGMAP_NAME) 도 인식 — 운영 문서가
// 혼용된 케이스에서 둘 다 동작하도록 양쪽 lookup.
func resolveAllowlistTarget() allowlistConfigMapTarget {
	ns := firstNonEmpty(
		os.Getenv("AIPAAS_ALLOWLIST_NAMESPACE"),
		os.Getenv("AGENT_NAMESPACE"),
		"aipaas-system",
	)
	name := firstNonEmpty(
		os.Getenv("AIPAAS_ALLOWLIST_CONFIGMAP_NAME"),
		os.Getenv("ALLOWLIST_CONFIGMAP"),
		"aipaas-agent-allowlist",
	)
	return allowlistConfigMapTarget{namespace: ns, name: name}
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}

// applyAgentConfig — 5 개 param 을 받아 ConfigMap data 를 갱신. 자세한 contract 는 file header 참조.
//
// 비결정성 (cs 가 nil): production 빌드에서 Clientset() 이 nil 이면 AGENT_UNAVAILABLE.
// 단위 테스트는 mockK8sClient.Clientset() 으로 fake clientset 주입.
func (d *Dispatcher) applyAgentConfig(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.kube == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "K8S_CLIENT_NIL",
			"K8s client not initialized")
	}
	cs := d.kube.Clientset()
	if cs == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "K8S_CLIENT_NIL",
			"K8s clientset not available")
	}

	// ─── 1. 파라미터 추출 + parse ─────────────────────────────────────────────
	allowedNamespacesRaw := getStringParam(cmd, "allowed_namespaces")
	allowedCommandsRaw := getStringParam(cmd, "allowed_commands")
	allowedChartsRaw := getStringParam(cmd, "allowed_charts")
	allowedExecNamespacesRaw := getStringParam(cmd, "allowed_exec_namespaces")
	resourcePolicyRaw := getStringParam(cmd, "resource_policy") // YAML pass-through
	// hybrid helm-repo sync. JSON array of objects (name/url/username/password/...).
	// ConfigMap 의 helm_repositories key 로 직접 write — agent 의 reconciler 가 본 key 변화 감지해 helm
	// SDK RepositoryFile 갱신. 본 step 은 push 만 — 등록 자체는 reconciler 책임.
	helmRepositoriesRaw := getStringParam(cmd, "helm_repositories")
	// fleet-wide OidcGroupBinding sync. JSON array of {name, spec}.
	// ConfigMap 의 oidc_bindings key 로 직접 write. operator (per-cluster) 또는 agent 자체 reconciler
	// 가 본 key 변화 감지해 ClusterRoleBinding / RoleBinding 으로 변환. operator path 가 default —
	// per-cluster operator 가 미설치된 cluster 는 본 key 는 ConfigMap 에 저장만 됨 (noop).
	// backward compat: 옛 backend 가 본 field 안 보내면 raw == "" → key 도 ConfigMap 에 빈 string 으로 저장.
	oidcBindingsRaw := getStringParam(cmd, "oidc_bindings")

	// 최소 하나는 명시되어야 함 — 전부 빈 경우 backend 의 잘못된 호출 가능성.
	if allowedNamespacesRaw == "" && allowedCommandsRaw == "" && allowedChartsRaw == "" {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "MISSING_PARAM",
			"at least one of allowed_namespaces, allowed_commands, allowed_charts required")
	}

	allowedNamespaces, perr := parseJSONStringArray("allowed_namespaces", allowedNamespacesRaw)
	if perr != nil {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "INVALID_PAYLOAD", perr.Error())
	}
	allowedCommands, perr := parseJSONStringArray("allowed_commands", allowedCommandsRaw)
	if perr != nil {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "INVALID_PAYLOAD", perr.Error())
	}
	allowedCharts, perr := parseJSONStringArray("allowed_charts", allowedChartsRaw)
	if perr != nil {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "INVALID_PAYLOAD", perr.Error())
	}
	allowedExecNamespaces, perr := parseJSONStringArray("allowed_exec_namespaces", allowedExecNamespacesRaw)
	if perr != nil {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "INVALID_PAYLOAD", perr.Error())
	}

	// ─── 2. ConfigMap data 빌드 ───────────────────────────────────────────────
	// allowlist.go 의 parseYAMLList 가 받아주는 형식: "- value\n" 의 YAML list. 안전을 위해 quote.
	newData := map[string]string{
		"allowed_charts":          yamlList(allowedCharts),
		"allowed_namespaces":      yamlList(allowedNamespaces),
		"allowed_commands":        yamlList(allowedCommands),
		"allowed_exec_namespaces": yamlList(allowedExecNamespaces),
		"resource_policy":         resourcePolicyRaw, // YAML pass-through (parser 가 빈 문자열을 nil 정책으로 인식).
		// helm_repositories JSON pass-through. reconciler 가 본 key 의
		// JSON array 를 parse 해 helm SDK 의 RepositoryFile 갱신. 빈 문자열이면 사용자가 명시적
		// 으로 비운 의도 — agent 의 모든 등록 repo 가 unregister.
		"helm_repositories": helmRepositoriesRaw,
		// oidc_bindings JSON pass-through. operator / agent reconciler 가 본 key
		// 의 JSON array 를 parse 해 ClusterRoleBinding/RoleBinding 으로 변환. 빈 문자열이면 모든 fleet
		// binding 제거. operator 미설치 cluster 에서는 본 key 는 ConfigMap 에 저장만 (consumer noop).
		"oidc_bindings": oidcBindingsRaw,
	}

	// ─── 3. Get → Update with optimistic concurrency, retry on Conflict ──────
	// ConfigMap update 후 Loader.Watch 가 자동 reload.
	target := resolveAllowlistTarget()
	var (
		newRV   string
		lastErr error
	)
	for attempt := 0; attempt < applyConfigMaxRetries; attempt++ {
		cm, err := cs.CoreV1().ConfigMaps(target.namespace).Get(ctx, target.name, metav1.GetOptions{})
		if err != nil {
			if k8serrors.IsNotFound(err) {
				return errorResponse(agentv1.Status_FAILED, "CONFIGMAP_NOT_FOUND",
					fmt.Sprintf("ConfigMap %s/%s not found", target.namespace, target.name))
			}
			if k8serrors.IsForbidden(err) {
				return errorResponse(agentv1.Status_FAILED, "FORBIDDEN",
					fmt.Sprintf("forbidden: %s", err.Error()))
			}
			return errorResponse(agentv1.Status_FAILED, "K8S_API_ERROR",
				fmt.Sprintf("get configmap: %s", err.Error()))
		}

		// data 통째 교체 — partial update 의 모호성 회피. 빈 entry 도 ConfigMap 에 빈 문자열로 저장.
		// 그래야 backend 가 의도적으로 "exec_namespaces 비움" 같은 reset 의도를 표현 가능.
		cm.Data = newData
		updated, uerr := cs.CoreV1().ConfigMaps(target.namespace).Update(ctx, cm, metav1.UpdateOptions{})
		if uerr == nil {
			newRV = updated.GetResourceVersion()
			slog.Info("apply_agent_config: ConfigMap updated",
				slog.String("namespace", target.namespace),
				slog.String("name", target.name),
				slog.String("resource_version", newRV),
				slog.Int("attempt", attempt+1))
			lastErr = nil
			break
		}
		lastErr = uerr
		if k8serrors.IsConflict(uerr) {
			slog.Debug("apply_agent_config: conflict — retrying",
				slog.Int("attempt", attempt+1),
				slog.String("error", uerr.Error()))
			continue
		}
		if k8serrors.IsForbidden(uerr) {
			return errorResponse(agentv1.Status_FAILED, "FORBIDDEN",
				fmt.Sprintf("forbidden: %s", uerr.Error()))
		}
		return errorResponse(agentv1.Status_FAILED, "K8S_API_ERROR",
			fmt.Sprintf("update configmap: %s", uerr.Error()))
	}
	if lastErr != nil {
		// 모든 retry 가 Conflict 로 실패 — 너무 잦은 동시 업데이트.
		return errorResponse(agentv1.Status_FAILED, "K8S_API_ERROR",
			fmt.Sprintf("update configmap: too many conflicts after %d retries: %s",
				applyConfigMaxRetries, lastErr.Error()))
	}

	// ─── 3.5. Hybrid helm-repo sync ────────────────────────────────────────
	// ConfigMap update 가 성공한 직후 helm SDK 의 RepositoryFile 도 갱신. agent restart 시에는
	// boot loader 가 ConfigMap 의 helm_repositories key 보고 다시 sync — 그래서 본 inline sync 가
	// 실패해도 영구 손상 없음 (best-effort, error 는 log + 응답에 포함).
	//
	// 빈 array 도 dispatch — anycloud-managed entry 의 orphan 정리. backend 에서 repo 가
	// 삭제되면 ConfigMap 의 helm_repositories 가 빈 array 가 되고, agent 는 그것을 받아
	// anycloud-* prefix entry 모두 RepositoryFile 에서 제거 (사용자 수동 추가는 보호).
	helmReposSynced := 0
	helmReposRemoved := 0
	var helmSyncErr string
	if d.helm != nil {
		entries, perr := helm.ParseRepoList(helmRepositoriesRaw)
		if perr != nil {
			helmSyncErr = "parse: " + perr.Error()
			slog.Warn("apply_agent_config: helm_repositories parse failed", slog.String("error", perr.Error()))
		} else {
			added, removed, serr := helm.SyncRepositoriesWithCleanup(d.helm.Settings(), entries)
			helmReposSynced = added
			helmReposRemoved = removed
			if serr != nil {
				helmSyncErr = "sync: " + serr.Error()
				slog.Warn("apply_agent_config: helm sync failed",
					slog.String("error", serr.Error()),
					slog.Int("added", added),
					slog.Int("removed", removed))
			} else {
				slog.Info("apply_agent_config: helm repositories synced",
					slog.Int("added", added),
					slog.Int("removed", removed))
			}
		}
	}

	// Agent OIDC binding reconciler 폐기. RBAC starter (backend) 가 K8s API 직접 apply.
	// oidcBindingsRaw 는 backend 가 빈 array 로 전송 (backward-compat — 옛 agent 호환). agent 는
	// ConfigMap 에 그대로 저장만 — 별도 K8s mutation 없음.
	_ = oidcBindingsRaw

	// ─── 4. OK 응답 ───────────────────────────────────────────────────────────
	result, err := structpb.NewStruct(map[string]interface{}{
		"applied":            true,
		"resource_version":   newRV,
		"namespace":          target.namespace,
		"configmap_name":     target.name,
		"agent_instance_id":  d.agentInstanceID,
		"helm_repos_synced":  float64(helmReposSynced),
		"helm_repos_removed": float64(helmReposRemoved),
		"helm_sync_error":    helmSyncErr,
	})
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "MARSHAL_FAILED", err.Error())
	}
	return okResponse(result)
}

// parseJSONStringArray — backend 가 ParamsBuilder 로 보낸 JSON array string 을 []string 으로.
// 빈 문자열은 (값 미지정) → 빈 slice + nil error. malformed 면 wrap 된 error.
func parseJSONStringArray(key, raw string) ([]string, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil, nil
	}
	var out []string
	if err := json.Unmarshal([]byte(raw), &out); err != nil {
		return nil, fmt.Errorf("%s: not a JSON array of strings (%v)", key, err)
	}
	return out, nil
}

// yamlList — []string → "- \"v1\"\n- \"v2\"\n" 형식. allowlist.go 의 parseYAMLList 가
// sigs.k8s.io/yaml 로 unmarshal 하므로 정확히 동일한 표현. 빈 slice 면 빈 문자열.
//
// quote 처리 이유: ConfigMap 의 YAML list value 에 "*" 가 들어오면 YAML alias 로 오해될 수 있어
// (helm chart 의 configmap.yaml 도 동일하게 quote 처리). dash 포함 chart-version range
// ("0.1.0-0.2.0") 도 quote 안 하면 YAML negative number 해석 위험.
func yamlList(items []string) string {
	if len(items) == 0 {
		return ""
	}
	var b strings.Builder
	for _, it := range items {
		b.WriteString("- ")
		b.WriteString(yamlQuote(it))
		b.WriteString("\n")
	}
	return b.String()
}

// yamlQuote — 문자열을 YAML double-quoted scalar 로 escape. backslash + double-quote 만 escape.
// 다른 control char 는 ConfigMap data 에 보통 들어오지 않으므로 best-effort.
func yamlQuote(s string) string {
	escaped := strings.ReplaceAll(s, `\`, `\\`)
	escaped = strings.ReplaceAll(escaped, `"`, `\"`)
	return `"` + escaped + `"`
}

