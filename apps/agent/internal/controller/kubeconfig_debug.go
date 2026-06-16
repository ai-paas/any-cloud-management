// GENERATE_KUBECONFIG 핸들러.
// CREATE_NODE_DEBUG_POD 핸들러.
package controller

import (
	"context"
	"encoding/base64"
	"fmt"
	"strconv"

	agentv1 "anycloud/agent/internal/gen/agent/v1"
	"anycloud/agent/internal/k8s"
	"google.golang.org/protobuf/types/known/structpb"
)

// generateKubeconfig — TokenRequest 발급 후 kubeconfig YAML 합성.
//
// AllowList 검증:
//   - GENERATE_KUBECONFIG command allowed
//   - 대상 namespace 가 allowed_namespaces 안에 있을 것
//
// params:
//   namespace, service_account (필수)
//   ttl_seconds (default 3600, max enforced by K8s ~24h)
//   cluster_name (kubeconfig 의 cluster name, default "aipaas-cluster")
//   context_namespace (kubeconfig context 의 default namespace, default same as namespace)
func (d *Dispatcher) generateKubeconfig(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.kube == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "K8S_CLIENT_NIL", "K8s client not initialized")
	}
	if d.allowlist == nil {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "ALLOWLIST_REQUIRED", "AllowList not loaded")
	}
	namespace := getStringParam(cmd, "namespace")
	serviceAccount := getStringParam(cmd, "service_account")
	if namespace == "" || serviceAccount == "" {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "MISSING_PARAM",
			"namespace, service_account required")
	}
	policy := d.allowlist.Snapshot()
	if !policy.IsNamespaceAllowed(namespace) {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "NAMESPACE_NOT_ALLOWED",
			fmt.Sprintf("namespace %s not in allowlist", namespace))
	}

	ttl, _ := strconv.ParseInt(getStringParam(cmd, "ttl_seconds"), 10, 64)
	clusterName := defaultStr(getStringParam(cmd, "cluster_name"), "aipaas-cluster")
	contextNs := defaultStr(getStringParam(cmd, "context_namespace"), namespace)

	tok, err := d.kube.IssueServiceAccountToken(ctx, k8s.TokenRequestOptions{
		Namespace:         namespace,
		ServiceAccount:    serviceAccount,
		ExpirationSeconds: ttl,
	})
	if err != nil {
		// SA 미존재 / RBAC 실패 등은 그대로 메시지에 노출.
		if isNotFound(err) {
			return errorResponse(agentv1.Status_FAILED, "SERVICE_ACCOUNT_NOT_FOUND", err.Error())
		}
		return errorResponse(agentv1.Status_FAILED, "TOKEN_REQUEST_FAILED", err.Error())
	}

	caData, caErr := d.kube.ServerCAData()
	if caErr != nil {
		return errorResponse(agentv1.Status_FAILED, "CA_DATA_UNAVAILABLE", caErr.Error())
	}
	server := d.kube.APIServerURL()
	if server == "" {
		return errorResponse(agentv1.Status_FAILED, "API_SERVER_URL_UNAVAILABLE", "no API server URL")
	}

	yaml := composeKubeconfigYAML(clusterName, server, caData, serviceAccount, tok.Token, contextNs)

	result, _ := structpb.NewStruct(map[string]interface{}{
		"kubeconfig_yaml":  yaml,
		"expires_at":       tok.ExpirationTimestamp.Format("2006-01-02T15:04:05Z07:00"),
		"service_account":  serviceAccount,
		"namespace":        namespace,
		"agent_instance_id": d.agentInstanceID,
	})
	return okResponse(result)
}

// composeKubeconfigYAML — 표준 kubeconfig v1 YAML 합성. token 기반 user.
func composeKubeconfigYAML(clusterName, server string, ca []byte, saName, token, contextNs string) string {
	caB64 := base64.StdEncoding.EncodeToString(ca)
	// 들여쓰기 2 spaces (kubectl 기본 형식과 동일).
	return fmt.Sprintf(`apiVersion: v1
kind: Config
clusters:
- name: %s
  cluster:
    server: %s
    certificate-authority-data: %s
users:
- name: aipaas-%s
  user:
    token: %s
contexts:
- name: aipaas-%s
  context:
    cluster: %s
    user: aipaas-%s
    namespace: %s
current-context: aipaas-%s
`, clusterName, server, caB64, saName, token, saName, clusterName, saName, contextNs, saName)
}

// isNotFound — K8s API 의 NotFound 에러 판정. errors.IsNotFound 와 동등 (의존성 회피용 string match).
func isNotFound(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	return contains(msg, "not found") || contains(msg, "NotFound")
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}

// createNodeDebugPod — host namespace + privileged debug pod 생성. 반환된 (ns, pod) 으로 caller 가
// PodExec 호출.
//
// AllowList: CREATE_NODE_DEBUG_POD command + 대상 namespace 둘 다 통과 필요.
func (d *Dispatcher) createNodeDebugPod(ctx context.Context, cmd *agentv1.CommandRequest) *agentv1.CommandResponse {
	if d.kube == nil {
		return errorResponse(agentv1.Status_AGENT_UNAVAILABLE, "K8S_CLIENT_NIL", "K8s client not initialized")
	}
	if d.allowlist == nil {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "ALLOWLIST_REQUIRED", "AllowList not loaded")
	}
	nodeName := getStringParam(cmd, "node_name")
	if nodeName == "" {
		return errorResponse(agentv1.Status_INVALID_PARAMS, "MISSING_PARAM", "node_name required")
	}
	namespace := defaultStr(getStringParam(cmd, "namespace"), "kube-system")
	policy := d.allowlist.Snapshot()
	if !policy.IsNamespaceAllowed(namespace) {
		return errorResponse(agentv1.Status_PERMISSION_DENIED, "NAMESPACE_NOT_ALLOWED",
			fmt.Sprintf("namespace %s not in allowlist", namespace))
	}

	ttl, _ := strconv.ParseInt(getStringParam(cmd, "ttl_seconds"), 10, 64)
	res, err := d.kube.CreateNodeDebugPod(ctx, k8s.NodeDebugPodOptions{
		NodeName:   nodeName,
		Namespace:  namespace,
		Image:      getStringParam(cmd, "image"),
		PodName:    getStringParam(cmd, "pod_name"),
		TTLSeconds: ttl,
	})
	if err != nil {
		return errorResponse(agentv1.Status_FAILED, "DEBUG_POD_CREATE_FAILED", err.Error())
	}
	result, _ := structpb.NewStruct(map[string]interface{}{
		"namespace":         res.Namespace,
		"pod_name":          res.PodName,
		"expires_at":        res.ExpiresAt.Format("2006-01-02T15:04:05Z07:00"),
		"node_name":         nodeName,
		"agent_instance_id": d.agentInstanceID,
	})
	return okResponse(result)
}
