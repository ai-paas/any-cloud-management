// ENSURE_AGENT_CONFIG_ANNOTATIONS 핸들러. ConfigMap 에 helm.sh/resource-policy: keep 이
// 없으면 붙인다. chart 가 이 annotation 을 넣기 전에 만들어진 ConfigMap 을 메우기 위한 것이다.
//
// data 는 건드리지 않고 annotation 만 고친다. 이미 붙어 있으면 Update 를 아예 부르지 않고
// already_present 로 답한다. backend 가 부팅마다 모든 cluster 에 호출해도 Get 한 번씩이라
// K8s API 부하가 거의 없다.
//
// error_code 는 apply_config.go 와 같다. CONFIGMAP_NOT_FOUND, FORBIDDEN, K8S_API_ERROR.
// 재시도는 ResourceVersion 충돌일 때만 한다.

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
