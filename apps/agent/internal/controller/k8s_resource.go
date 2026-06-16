// K8s 리소스 핸들러.
//
// 본 파일이 처리하는 명령:
//   - LIST_PODS            : namespace 단위 Pod 목록 (all-namespaces sentinel "_all")
//   - GET_LOG              : Pod / container 로그 (tail/previous/sinceSeconds 옵션)
//   - DELETE_RESOURCE      : RESTMapper 기반 동적 자원 삭제 (CRD 포함)
//   - APPLY_MANIFEST       : multi-doc YAML server-side apply + namespace allowlist 사후검증
//   - LIST_RESOURCES       : paginated 자원 list (continueToken / labelSelector)
//   - GET_RESOURCE         : 단일 자원 JSON 반환
//   - GET_CLUSTER_INFO     : cluster UID / version / node count / API endpoint
//   - LIST_RESOURCE_KINDS  : discovery API enumerate (UI resource picker)
//   - RESOLVE_RESOURCE     : 단일 입력 (short/singular/plural) → ResolvedResource + fuzzy suggestion
//
// 공통 access 검사: checkResourceAccess(kind, namespace) — RESTMapper resolve →
// namespace allowlist (namespaced 자원만) → ResourcePolicy. nil 반환 = 허용.

package controller

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	agentv1 "anycloud/agent/internal/gen/agent/v1"
	"anycloud/agent/internal/k8s"
	"google.golang.org/protobuf/types/known/structpb"
)

func (d *Dispatcher) listPods(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.kube == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "K8S_CLIENT_NIL", "K8s client not initialized")
	}
	namespace := getStringParam(cmd, "namespace")
	if namespace == "_all" {
		namespace = ""
	}
	pods, err := d.kube.ListPods(ctx, namespace)
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "K8S_LIST_PODS_FAILED", err.Error())
	}
	podList := make([]interface{}, 0, len(pods))
	for _, p := range pods {
		podList = append(podList, map[string]interface{}{
			"name": p.Name, "namespace": p.Namespace, "phase": p.Phase,
			"node": p.NodeName, "podIP": p.PodIP,
			"containersReady": float64(p.ContainersReady),
			"containersTotal": float64(p.ContainersTotal),
			"startTime":       p.StartTime.Format(time.RFC3339),
		})
	}
	result, err := structpb.NewStruct(map[string]interface{}{
		"namespace":         namespace,
		"count":             float64(len(pods)),
		"pods":              podList,
		"agent_instance_id": d.agentInstanceID,
	})
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "SERIALIZE_FAILED", err.Error())
	}
	return okResponse(result)
}

func (d *Dispatcher) getLog(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.kube == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "K8S_CLIENT_NIL", "K8s client not initialized")
	}
	pod := getStringParam(cmd, "pod")
	if pod == "" {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "MISSING_PARAM", "pod required")
	}
	namespace := getStringParam(cmd, "namespace")
	if namespace == "" {
		namespace = "default"
	}
	opts := k8s.PodLogsOptions{
		Namespace: namespace, Pod: pod, Container: getStringParam(cmd, "container"),
		TailLines:    parseInt64(getStringParam(cmd, "tailLines"), 100),
		Previous:     parseBool(getStringParam(cmd, "previous")),
		SinceSeconds: parseInt64(getStringParam(cmd, "sinceSeconds"), 0),
		MaxBytes:     1 << 20,
	}
	logs, err := d.kube.GetPodLogs(ctx, opts)
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "K8S_GET_LOG_FAILED", err.Error())
	}
	result, _ := structpb.NewStruct(map[string]interface{}{
		"pod": pod, "namespace": namespace, "container": opts.Container,
		"tail_lines":        float64(opts.TailLines),
		"previous":          opts.Previous,
		"since_seconds":     float64(opts.SinceSeconds),
		"log":               logs,
		"length_bytes":      float64(len(logs)),
		"agent_instance_id": d.agentInstanceID,
	})
	return okResponse(result)
}

// DELETE_RESOURCE. RESTMapper 기반 동적 자원 해석 → namespace + resource policy 검사 → delete.
// ANY kind (CRD 포함) 처리.
func (d *Dispatcher) deleteResource(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.kube == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "K8S_CLIENT_NIL", "K8s client not initialized")
	}
	kind := getStringParam(cmd, "kind")
	name := getStringParam(cmd, "name")
	namespace := getStringParam(cmd, "namespace")
	if kind == "" || name == "" {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "MISSING_PARAM", "kind and name required")
	}
	if denied := d.checkResourceAccess(kind, namespace); denied != nil {
		return denied
	}

	err := d.kube.DeleteResource(ctx, k8s.DeleteResourceOptions{
		Kind: kind, Namespace: namespace, Name: name,
	})
	if err != nil {
		// k8s.ErrUnsupportedKind → INVALID_PARAMS. 기타 → FAILED.
		if errors.Is(err, k8s.ErrUnsupportedKind) {
			return errorResponse(agentv1.Status_INVALID_PARAMS, "UNSUPPORTED_KIND", err.Error())
		}
		return errorResponse(agentv1.Status_FAILED, "K8S_DELETE_FAILED", err.Error())
	}

	result, _ := structpb.NewStruct(map[string]interface{}{
		"kind": kind, "name": name, "namespace": namespace,
		"deleted":           true,
		"agent_instance_id": d.agentInstanceID,
	})
	return okResponse(result)
}

// APPLY_MANIFEST. multi-doc YAML 받아 server-side apply.
//
// AllowList 검증:
//  1. namespace allowlist (default namespace 또는 manifest 안 명시된 namespace 모두)
//  2. cluster-scoped 자원 (Namespace, ClusterRole 등) 은 namespace allowlist 우회 — 추가 정책 권장
//
// 응답: applied[] 배열 (각 자원의 metadata).
func (d *Dispatcher) applyManifest(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.kube == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "K8S_CLIENT_NIL", "K8s client not initialized")
	}
	manifest := getStringParam(cmd, "manifest")
	if manifest == "" {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "MISSING_PARAM", "manifest required")
	}
	defaultNamespace := getStringParam(cmd, "namespace")
	if defaultNamespace == "_all" || defaultNamespace == "-" {
		defaultNamespace = "" // sentinel — cluster-scoped or all-namespaces.
	}

	// AllowList — default namespace 가 비어있지 않으면 검사.
	// (cluster-scoped resource 만 있는 manifest 라면 namespace 빈 채로 보내야 함.)
	if defaultNamespace != "" && d.allowlist != nil &&
		!d.allowlist.Snapshot().IsNamespaceAllowed(defaultNamespace) {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "NAMESPACE_NOT_ALLOWED",
			fmt.Sprintf("namespace %s not in allowlist", defaultNamespace))
	}

	dryRun := parseBool(getStringParam(cmd, "dry_run"))
	result, err := d.kube.ApplyManifest(ctx, k8s.ApplyManifestOptions{
		Manifest:         manifest,
		DefaultNamespace: defaultNamespace,
		FieldManager:     "aipaas-agent",
		Force:            parseBool(getStringParam(cmd, "force")),
		DryRun:           dryRun,
	})
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "K8S_APPLY_FAILED", err.Error())
	}

	// Manifest 안의 각 자원이 namespaced 인데 allowlist 외 namespace 면 사후 검증 필요.
	// (default ns 만 검사하면 manifest 가 명시한 다른 ns 를 우회 가능 — defense-in-depth.)
	if d.allowlist != nil {
		policy := d.allowlist.Snapshot()
		for _, r := range result.Applied {
			if r.Namespace != "" && !policy.IsNamespaceAllowed(r.Namespace) {
				return errorResponse(agentv1.Status_PERMISSION_DENIED, "NAMESPACE_NOT_ALLOWED_POST_APPLY",
					fmt.Sprintf("applied resource targeted namespace %s outside allowlist (kind=%s name=%s)",
						r.Namespace, r.Kind, r.Name))
			}
		}
	}

	appliedList := make([]interface{}, 0, len(result.Applied))
	for _, r := range result.Applied {
		appliedList = append(appliedList, map[string]interface{}{
			"apiVersion":      r.APIVersion,
			"kind":            r.Kind,
			"name":            r.Name,
			"namespace":       r.Namespace,
			"resourceVersion": r.ResourceVersion,
			"uid":             r.UID,
		})
	}
	resp, _ := structpb.NewStruct(map[string]interface{}{
		"applied":           appliedList,
		"applied_count":     float64(len(result.Applied)),
		"agent_instance_id": d.agentInstanceID,
		"dry_run":           dryRun,
	})
	return okResponse(resp)
}

// LIST_RESOURCES (paginated K8s). RESTMapper 기반 동적 자원 해석 → policy/namespace 검사 → list.
// Result: items (JSON array), continue_token (다음 페이지), returned_count.
func (d *Dispatcher) listResources(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.kube == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "K8S_CLIENT_NIL", "K8s client not initialized")
	}
	kind := getStringParam(cmd, "kind")
	if kind == "" {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "MISSING_PARAM", "kind required")
	}
	namespace := getStringParam(cmd, "namespace")
	if namespace == "_all" {
		namespace = "" // all-namespaces sentinel — backend convention 과 일치.
	}
	if denied := d.checkResourceAccess(kind, namespace); denied != nil {
		return denied
	}

	limit := parseInt64(getStringParam(cmd, "limit"), 50)
	if limit < 1 {
		limit = 50
	}
	if limit > 500 {
		limit = 500
	}
	result, err := d.kube.ListResources(ctx, k8s.ListResourcesOptions{
		Kind:          kind,
		Namespace:     namespace,
		Limit:         limit,
		ContinueToken: getStringParam(cmd, "continueToken"),
		LabelSelector: getStringParam(cmd, "labelSelector"),
	})
	if err != nil {
		if errors.Is(err, k8s.ErrUnsupportedKind) {
			return errorResponse(agentv1.Status_INVALID_PARAMS, "UNSUPPORTED_KIND", err.Error())
		}
		return errorResponse(agentv1.Status_FAILED, "K8S_LIST_FAILED", err.Error())
	}
	resp, _ := structpb.NewStruct(map[string]interface{}{
		"kind":              kind,
		"namespace":         namespace,
		"items":             result.Items, // JSON-encoded list (caller parse).
		"continue_token":    result.ContinueToken,
		"returned_count":    float64(result.ReturnedCount),
		"agent_instance_id": d.agentInstanceID,
	})
	return okResponse(resp)
}

// GET_RESOURCE. RESTMapper 기반 동적 자원 해석 → policy/namespace 검사 → get → JSON 반환.
// Read-only 명령이지만 일관성 위해 namespace allowlist + ResourcePolicy 적용 (cluster-scoped 는 namespace bypass).
func (d *Dispatcher) getResource(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.kube == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "K8S_CLIENT_NIL", "K8s client not initialized")
	}
	kind := getStringParam(cmd, "kind")
	name := getStringParam(cmd, "name")
	namespace := getStringParam(cmd, "namespace")
	if kind == "" || name == "" {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "MISSING_PARAM", "kind and name required")
	}
	if denied := d.checkResourceAccess(kind, namespace); denied != nil {
		return denied
	}

	jsonStr, err := d.kube.GetResource(ctx, k8s.GetResourceOptions{
		Kind: kind, Namespace: namespace, Name: name,
	})
	if err != nil {
		if errors.Is(err, k8s.ErrUnsupportedKind) {
			return errorResponse(agentv1.Status_INVALID_PARAMS, "UNSUPPORTED_KIND", err.Error())
		}
		return errorResponse(agentv1.Status_FAILED, "K8S_GET_FAILED", err.Error())
	}

	result, _ := structpb.NewStruct(map[string]interface{}{
		"kind": kind, "name": name, "namespace": namespace,
		"resource":          jsonStr, // JSON-encoded K8s object (caller 가 unmarshal).
		"length_bytes":      float64(len(jsonStr)),
		"agent_instance_id": d.agentInstanceID,
	})
	return okResponse(result)
}

// checkResourceAccess — LIST/GET/DELETE 명령의 공통 access 검사. resolveResource (RESTMapper)
// + namespace allowlist + ResourcePolicy 를 한 곳에서 평가.
//
// 흐름:
//  1. k8s.Client.ResolveResource(kind) — kind 문자열을 plural+scope 으로 정규화 (CRD 포함).
//     실패 → INVALID_PARAMS / UNSUPPORTED_KIND.
//  2. Namespaced 자원이면 namespace allowlist 검사. cluster-scoped 자원은 namespace 검사 skip.
//  3. ResourcePolicy.IsResourceKindAllowed(plural, ns) — kind-level 정책 검사
//     (nil 이면 legacy allow-all).
//
// nil 반환 = 허용. non-nil 반환 = denial response (caller 가 그대로 return).
func (d *Dispatcher) checkResourceAccess(kind, namespace string) *agentv1.CommandResponse {
	resolved, err := d.kube.ResolveResource(kind)
	if err != nil {
		if errors.Is(err, k8s.ErrUnsupportedKind) {
			return errorResponse(agentv1.Status_INVALID_PARAMS, "UNSUPPORTED_KIND", err.Error())
		}
		// Discovery / RESTMapper 자체 오류는 일시적 — FAILED 로 분류 (재시도 가능).
		return errorResponse(agentv1.Status_FAILED, "K8S_RESOLVE_FAILED", err.Error())
	}

	// Namespace allowlist — namespaced 자원 + 명시 namespace 인 경우만 검사.
	// all-namespaces sentinel ("") 는 통과 — namespace allowlist 가 빈 문자열을 매칭하지 않으므로
	// 별도 short-circuit (기존 동작 보존).
	if resolved.Namespaced && namespace != "" && d.allowlist != nil &&
		!d.allowlist.Snapshot().IsNamespaceAllowed(namespace) {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "NAMESPACE_NOT_ALLOWED",
			fmt.Sprintf("namespace %s not in allowlist", namespace))
	}

	// ResourcePolicy — Plural 정규화된 형태로 검사. cluster-scoped 자원은 namespace="" 로
	// (ResourceRule 의 Namespace="" 는 "어떤 ns 든" 의미 — cluster-scoped 와 자연스럽게 호환).
	checkNs := namespace
	if !resolved.Namespaced {
		checkNs = ""
	}
	if d.allowlist != nil && !d.allowlist.Snapshot().IsResourceKindAllowed(resolved.Plural, checkNs) {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "RESOURCE_KIND_DENIED",
			fmt.Sprintf("kind %s (resolved: %s) denied by resource policy in namespace %q",
				kind, resolved.Plural, namespace))
	}
	return nil
}

func (d *Dispatcher) clusterInfo(ctx context.Context) *agentv1.CommandResponse {
	if d.kube == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "K8S_CLIENT_NIL", "K8s client not initialized")
	}
	info, err := d.kube.ClusterInfo(ctx)
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "K8S_CLUSTER_INFO_FAILED", err.Error())
	}
	result, _ := structpb.NewStruct(map[string]interface{}{
		"k8s_cluster_uid":     info.K8sClusterUID,
		"version":             info.Version,
		"distribution":        info.Distribution,
		"node_count":          float64(info.NodeCount),
		"api_server_endpoint": info.APIServerEndpoint,
		"agent_instance_id":   d.agentInstanceID,
	})
	return okResponse(result)
}

// LIST_RESOURCE_KINDS — discovery API 로 cluster 가 지원하는 모든 API resource (kind) enumerate.
// CRD 도 자연스럽게 포함. backend 는 UI 의 "resource kind picker" 채울 때 사용.
//
// AllowList: 명령 자체 allowlist 만 — cluster-wide discovery 라 namespace 검사 없음.
// ResourcePolicy 도 미적용 (정책은 LIST/GET/DELETE 같은 행동에 적용. 목록 자체는 metadata).
func (d *Dispatcher) listResourceKinds(ctx context.Context) *agentv1.CommandResponse {
	if d.kube == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "K8S_CLIENT_NIL", "K8s client not initialized")
	}
	resources, err := d.kube.ListAPIResources(ctx)
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "DISCOVERY_FAILED", err.Error())
	}
	items := make([]interface{}, 0, len(resources))
	for _, r := range resources {
		shortNames := make([]interface{}, 0, len(r.ShortNames))
		for _, sn := range r.ShortNames {
			shortNames = append(shortNames, sn)
		}
		items = append(items, map[string]interface{}{
			"plural":      r.Plural,
			"singular":    r.Singular,
			"kind":        r.Kind,
			"group":       r.Group,
			"version":     r.Version,
			"namespaced":  r.Namespaced,
			"short_names": shortNames,
		})
	}
	data, err := structpb.NewStruct(map[string]interface{}{
		"kinds":             items,
		"count":             float64(len(resources)),
		"agent_instance_id": d.agentInstanceID,
	})
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "MARSHAL_FAILED", err.Error())
	}
	return okResponse(data)
}

// RESOLVE_RESOURCE — 입력 (단축이름/singular/plural/CRD 등) 을 정규화한 ResolvedResource 반환.
//
// LIST_RESOURCE_KINDS 는 cluster 가 지원하는 모든 kind 를 한 번에 enumerate (browser-friendly).
// 본 명령은 그 보완 — UI 의 단일 입력 box 에서 사용자가 친 한 단어를 정규화.
//
// 실패 시 (RESTMapper 가 못 푸는 typo 등) Levenshtein fuzzy match top-3 suggestion 동봉.
//
// AllowList: 명령 자체 allowlist 만. cluster-wide metadata operation 이라 namespace / policy 검사 없음.
func (d *Dispatcher) resolveResource(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	input := getStringParam(cmd, "input")
	if input == "" {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "MISSING_PARAM", "input required")
	}
	if d.kube == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "K8S_CLIENT_NIL", "K8s client not initialized")
	}

	resolved, err := d.kube.ResolveResource(input)
	if err == nil {
		shortNames := make([]interface{}, 0, len(resolved.ShortNames))
		for _, sn := range resolved.ShortNames {
			shortNames = append(shortNames, sn)
		}
		data, mErr := structpb.NewStruct(map[string]interface{}{
			"plural":            resolved.Plural,
			"singular":          resolved.Singular,
			"kind":              resolved.Kind,
			"group":             resolved.Group,
			"version":           resolved.Version,
			"namespaced":        resolved.Namespaced,
			"short_names":       shortNames,
			"agent_instance_id": d.agentInstanceID,
		})
		if mErr != nil {
			return errorResponse(agentv1.Status_FAILED, "MARSHAL_FAILED", mErr.Error())
		}
		return okResponse(data)
	}

	// Resolution 실패 — fuzzy suggestion 으로 응답 보강. ListAPIResources 도 실패하면
	// suggestions 비워두고 UNSUPPORTED_KIND 만 반환 (best-effort).
	suggestions := []interface{}{}
	if kinds, derr := d.kube.ListAPIResources(ctx); derr == nil {
		for _, t := range k8s.TopKByLevenshtein(strings.ToLower(strings.TrimSpace(input)), kinds, 3) {
			suggestions = append(suggestions, t)
		}
	}
	data, _ := structpb.NewStruct(map[string]interface{}{
		"suggestions":       suggestions,
		"input":             input,
		"agent_instance_id": d.agentInstanceID,
	})
	return failedWithData(agentv1.Status_INVALID_PARAMS, "UNSUPPORTED_KIND", err.Error(), data)
}
