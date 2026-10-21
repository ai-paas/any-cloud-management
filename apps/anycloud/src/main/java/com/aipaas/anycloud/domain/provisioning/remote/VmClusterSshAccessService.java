package com.aipaas.anycloud.domain.provisioning.remote;

import java.util.List;
import java.util.Map;

/** VM cluster 노드 정보 + SSH 접속 자료 노출. */
public interface VmClusterSshAccessService {

    /**
     * 노드 목록: DB 에 저장된 sanitized outputs 에서 즉시 반환 (Pulumi 호출 없음, ~ms).
     */
    NodeListResult listNodes(String clusterName);

    /**
     * SSH private key 발급: Pulumi state 에서 secret 을 live 복호화 (수 초).
     * Backend 는 gateway 뒤에서 운영되므로 평문 PEM 반환은 의도된 설계.
     */
    SshKeyResult issueSshKey(String clusterName);

    /**
     * @param sshJump 사설망 노드로 점프 호스트 경유가 필요하면 {@code user@host[:port]}, 아니면 null.
     *     비밀번호 미포함. 화면의 명령 구성에는 주소만 필요
     */
    record NodeListResult(
            String clusterName, String provider, String sshUser, String sshJump, List<Map<String, Object>> nodes) {}

    record SshKeyResult(String clusterName, String sshUser, String privateKeyPem, List<NodeSshInfo> nodes) {

        /** @param sshPort NAT 뒤 노드는 노드마다 다른 포트. sshCommand 의 {@code -p} 와 같은 값 */
        public record NodeSshInfo(String role, String publicIp, String privateIp, int sshPort, String sshCommand) {}
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
