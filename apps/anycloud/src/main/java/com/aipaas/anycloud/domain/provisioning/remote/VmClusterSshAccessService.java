package com.aipaas.anycloud.domain.provisioning.remote;

import java.util.List;
import java.util.Map;

/**
 * VM cluster 노드 정보 + SSH 접속 자료 노출.
 *
 * <p>Pulumi 가 cluster 마다 SSH keypair 를 생성해 state 에 secret 으로 보관하는데, 운영자가
 * 그 키를 얻을 방법이 없어 노드에 직접 접속할 수 없었다. 표준 {@code ProvisioningOutput}
 * 스키마 (nodes + sshPrivateKeyPem) 기반이라 8개 CSP 모두 동일하게 동작.
 */
public interface VmClusterSshAccessService {

    /**
     * 노드 목록 — DB 에 저장된 sanitized outputs 에서 즉시 반환 (Pulumi 호출 없음, ~ms).
     */
    NodeListResult listNodes(String clusterName);

    /**
     * SSH private key 발급 — Pulumi state 에서 secret 을 live 복호화 (수 초).
     * Backend 는 gateway 뒤에서 운영되므로 평문 PEM 반환은 의도된 설계.
     */
    SshKeyResult issueSshKey(String clusterName);

    record NodeListResult(String clusterName, String provider, String sshUser, List<Map<String, Object>> nodes) {}

    record SshKeyResult(String clusterName, String sshUser, String privateKeyPem, List<NodeSshInfo> nodes) {

        public record NodeSshInfo(String role, String publicIp, String privateIp, String sshCommand) {}
    }

    class VmClusterSshAccessException extends RuntimeException {
        private final String errorCode;

        public VmClusterSshAccessException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
