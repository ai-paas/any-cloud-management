// Ensure ConfigMap annotation backfill — ENSURE_AGENT_CONFIG_ANNOTATIONS handler.
//
// Helm chart 이 ConfigMap 에 `helm.sh/resource-policy: keep` annotation 을 붙이기 전 (legacy
// 배포) 에 만들어진 ConfigMap 을 멱등적으로 backfill. data 는 절대 건드리지 않음 — annotation
// 만 patch. backend 가 startup 시 모든 ACTIVE cluster 에 호출 → 안전하게 반복 호출 가능.
//
// 데이터 흐름:
//
//	backend ──(ENSURE_AGENT_CONFIG_ANNOTATIONS)──▶ dispatcher.ensureAgentConfigAnnotations
//	                                                       │
//	                                                       ▼
//	                                       CoreV1().ConfigMaps(ns).Get(name)
//	                                                       │
//	                                                       ▼ annotation 검사
//	                                       이미 keep ──▶ already_present=true, no Update
//	                                       없음    ──▶ annotations[helm.sh/resource-policy]=keep
//	                                                       │  ↺ Conflict 시 3 회 재시도
//	                                                       ▼
//	                                       return {ensured, resource_version, already_present}
//
// 에러 분류 — apply_config.go 와 동일 분류 (data 만 안 건드릴 뿐 K8s ops 는 같은 RBAC path).
//
//	NotFound          → status=FAILED + error_code=CONFIGMAP_NOT_FOUND
//	Forbidden (RBAC)  → status=FAILED + error_code=FORBIDDEN
//	기타 K8s API      → status=FAILED + error_code=K8S_API_ERROR
//
// 멱등성 (HHH 의 핵심 가치): 이미 keep annotation 이 있으면 Update API 자체를 호출하지 않음
// (already_present=true). 그래서 backend 가 부팅마다 N 개 cluster 모두에 호출해도 K8s API
// 부하가 거의 0 (Get 한 번씩만). retry 도 Conflict 만 retry — Forbidden/NotFound 는 즉시 종료.

package controller

import (
	"context"
	"fmt"
	"log/slog"

	agentv1 "anycloud/agent/internal/gen/agent/v1"
	"google.golang.org/protobuf/types/known/structpb"
	k8serrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

const (
	// helmResourcePolicyAnnotation — Helm 이 release uninstall 시 본 annotation 을 보면 K8s 자원을
	// 보존. ClusterAgent ConfigMap 이 사용자의 정책 (allowlist) 을 담고 있어 helm upgrade 사이클
	// 에서 분실되면 안 됨.
	helmResourcePolicyAnnotation = "helm.sh/resource-policy"
	helmResourcePolicyKeep       = "keep"
)

// ensureAgentConfigAnnotations — annotation 만 멱등적으로 추가. data 미변경. 자세한 contract 는
// file header 참조.
//
// AllowList: 호출 진입 시 commandAllowed (dispatcher.Handle) 가 이미 검사 — 본 handler 에서 추가
// 검사 불필요. 단 K8s client nil 은 production 빌드 sanity check (test 에선 mock 주입).
func (d *Dispatcher) ensureAgentConfigAnnotations(ctx context.Context, _ *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.kube == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "K8S_CLIENT_NIL",
			"K8s client not initialized")
	}
	cs := d.kube.Clientset()
	if cs == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "K8S_CLIENT_NIL",
			"K8s clientset not available")
	}

	target := resolveAllowlistTarget()

	var (
		alreadyPresent bool
		newRV          string
		lastErr        error
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

		// 이미 적용된 경우 — Update API 호출 없이 즉시 성공. 멱등성 핵심.
		if cm.Annotations != nil && cm.Annotations[helmResourcePolicyAnnotation] == helmResourcePolicyKeep {
			alreadyPresent = true
			newRV = cm.GetResourceVersion()
			lastErr = nil
			break
		}

		if cm.Annotations == nil {
			cm.Annotations = map[string]string{}
		}
		cm.Annotations[helmResourcePolicyAnnotation] = helmResourcePolicyKeep

		updated, uerr := cs.CoreV1().ConfigMaps(target.namespace).Update(ctx, cm, metav1.UpdateOptions{})
		if uerr == nil {
			newRV = updated.GetResourceVersion()
			slog.Info("ensure_agent_config_annotations: annotation added",
				slog.String("namespace", target.namespace),
				slog.String("name", target.name),
				slog.String("resource_version", newRV),
				slog.Int("attempt", attempt+1))
			lastErr = nil
			break
		}
		lastErr = uerr
		if k8serrors.IsConflict(uerr) {
			slog.Debug("ensure_agent_config_annotations: conflict — retrying",
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
		// 모든 retry 가 Conflict 로 실패. apply_config.go 와 동일한 메시지 패턴.
		return errorResponse(agentv1.Status_FAILED, "K8S_API_ERROR",
			fmt.Sprintf("update configmap: too many conflicts after %d retries: %s",
				applyConfigMaxRetries, lastErr.Error()))
	}

	result, err := structpb.NewStruct(map[string]interface{}{
		"ensured":           true,
		"resource_version":  newRV,
		"already_present":   alreadyPresent,
		"namespace":         target.namespace,
		"configmap_name":    target.name,
		"agent_instance_id": d.agentInstanceID,
	})
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "MARSHAL_FAILED", err.Error())
	}
	return okResponse(result)
}
