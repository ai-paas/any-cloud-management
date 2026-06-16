// Agent 설정 핸들러.
//
// 본 파일이 처리하는 명령:
//   - GET_AGENT_CONFIG : agent 의 in-memory allowlist + resource_policy snapshot 직렬화
//
// 운영자가 ConfigMap 을 edit 한 후 agent 가 watch 로 reload 했는지 검증할 때 사용 —
// last_reload_at 과 configmap_resource_version 으로 동기화 상태 확인 가능.
//
// AllowList: 명령 자체 allowlist 만 검사 (자기 정책 노출 — read-only).
//
// helper:
//   - buildResourcePolicyStruct : ResourcePolicy → wire shape (map) 변환
//   - sortInterfaceStrings      : map iteration order 비결정성 제거 (UI list 안정 정렬)

package controller

import (
	"context"
	"time"

	"anycloud/agent/internal/config"
	agentv1 "anycloud/agent/internal/gen/agent/v1"
	"google.golang.org/protobuf/types/known/structpb"
)

// GET_AGENT_CONFIG — agent 의 in-memory allowlist + resource_policy snapshot 직렬화.
//
// 운영자가 ConfigMap 을 edit 한 후 agent 가 watch 로 reload 했는지 검증할 때 사용 — last_reload_at
// 과 configmap_resource_version 으로 동기화 상태 확인 가능.
//
// AllowList: 명령 자체 allowlist 만 검사 (자기 정책 노출 — read-only).
func (d *Dispatcher) getAgentConfig(_ context.Context, _ *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.allowlist == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "ALLOWLIST_NIL",
			"allowlist loader not initialized")
	}
	snapshot := d.allowlist.Snapshot()
	meta := d.allowlist.MetaSnapshot()

	allowedNamespaces := make([]interface{}, 0, len(snapshot.Namespaces)+1)
	if snapshot.AllowAllNamespaces {
		allowedNamespaces = append(allowedNamespaces, "*")
	}
	for ns := range snapshot.Namespaces {
		allowedNamespaces = append(allowedNamespaces, ns)
	}
	sortInterfaceStrings(allowedNamespaces) // 결정성 — UI 의 list 안정 정렬.

	allowedExecNamespaces := make([]interface{}, 0, len(snapshot.ExecNamespaces)+1)
	if snapshot.AllowAllExecNamespaces {
		allowedExecNamespaces = append(allowedExecNamespaces, "*")
	}
	for ns := range snapshot.ExecNamespaces {
		allowedExecNamespaces = append(allowedExecNamespaces, ns)
	}
	sortInterfaceStrings(allowedExecNamespaces)

	allowedCommands := make([]interface{}, 0, len(snapshot.Commands))
	for c := range snapshot.Commands {
		allowedCommands = append(allowedCommands, c)
	}
	sortInterfaceStrings(allowedCommands)

	allowedCharts := make([]interface{}, 0, len(snapshot.Charts))
	for _, r := range snapshot.Charts {
		// 직렬화 형식 — ConfigMap 의 입력 형식 그대로 (round-trip-able).
		s := r.Repo + "/" + r.Chart + ":" + r.MinVersion
		if r.MinVersion != r.MaxVersion {
			s += "-" + r.MaxVersion
		}
		allowedCharts = append(allowedCharts, s)
	}

	resourcePolicy := buildResourcePolicyStruct(snapshot.ResourcePolicy)

	lastReloadStr := ""
	if !meta.LastReloadAt.IsZero() {
		lastReloadStr = meta.LastReloadAt.UTC().Format(time.RFC3339)
	}

	data, err := structpb.NewStruct(map[string]interface{}{
		"allowed_namespaces":         allowedNamespaces,
		"allow_all_namespaces":       snapshot.AllowAllNamespaces,
		"allowed_commands":           allowedCommands,
		"allowed_charts":             allowedCharts,
		"allowed_exec_namespaces":    allowedExecNamespaces,
		"allow_all_exec_namespaces":  snapshot.AllowAllExecNamespaces,
		"resource_policy":            resourcePolicy,
		"last_reload_at":             lastReloadStr,
		"configmap_resource_version": meta.ConfigMapResourceVersion,
		"agent_instance_id":          d.agentInstanceID,
	})
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "MARSHAL_FAILED", err.Error())
	}
	return okResponse(data)
}

// buildResourcePolicyStruct — ResourcePolicy 를 GET_AGENT_CONFIG 의 wire shape (map) 로 변환.
// nil 정책 (legacy) 은 mode="" + 빈 슬라이스로 노출 — UI 가 "정책 미설정" 으로 인지.
func buildResourcePolicyStruct(rp *config.ResourcePolicy) map[string]interface{} {
	out := map[string]interface{}{
		"mode":  "",
		"deny":  []interface{}{},
		"allow": []interface{}{},
	}
	if rp == nil {
		return out
	}
	out["mode"] = rp.Mode
	deny := make([]interface{}, 0, len(rp.Deny))
	for _, r := range rp.Deny {
		deny = append(deny, map[string]interface{}{
			"kind": r.Kind, "namespace": r.Namespace,
		})
	}
	out["deny"] = deny
	allow := make([]interface{}, 0, len(rp.Allow))
	for _, r := range rp.Allow {
		allow = append(allow, map[string]interface{}{
			"kind": r.Kind, "namespace": r.Namespace,
		})
	}
	out["allow"] = allow
	return out
}

// sortInterfaceStrings — interface{} slice 의 string value 만 정렬. map 의 set iteration 이
// random order 이므로 응답 결정성을 위해 사용.
func sortInterfaceStrings(s []interface{}) {
	sortStable(s, func(i, j int) bool {
		si, _ := s[i].(string)
		sj, _ := s[j].(string)
		return si < sj
	})
}
