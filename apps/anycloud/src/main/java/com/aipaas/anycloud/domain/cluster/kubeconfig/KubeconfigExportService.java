package com.aipaas.anycloud.domain.cluster.kubeconfig;

/**
 * Agent 통한 short-lived kubeconfig 발급.
 *
 * <p>사용자 vision 의 "kubeconfig 다운로드" 편의 기능. Cluster API 직접 노출 없이 agent 가 TokenRequest
 * API 로 한시 token 발급 후 표준 kubeconfig YAML 합성하여 반환.
 *
 * <p>보안 고려:
 * <ul>
 *   <li>caller 가 namespace + service_account 명시 — AllowList 가 namespace 검증</li>
 *   <li>TTL 기본 3600s (1h) — agent 가 K8s 의 expirationSeconds 로 강제</li>
 *   <li>발급된 token 은 1 회 발급 후 backend 측 저장 없음 — 사용자에게만 전달</li>
 * </ul>
 */
public interface KubeconfigExportService {

    IssuedKubeconfig issue(String clusterName, IssueRequest request);

    record IssueRequest(
            String namespace,
            String serviceAccount,
            Long ttlSeconds,
            String clusterDisplayName, // null 이면 backend cluster name 사용
            String contextNamespace) { // null 이면 namespace 사용
    }

    record IssuedKubeconfig(
            String clusterName, String namespace, String serviceAccount, String expiresAt, String kubeconfigYaml) {}

    class KubeconfigExportException extends RuntimeException {
        private final String errorCode;

        public KubeconfigExportException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public KubeconfigExportException(String errorCode, String message, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
