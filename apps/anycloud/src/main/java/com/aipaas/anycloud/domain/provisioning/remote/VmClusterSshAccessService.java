package com.aipaas.anycloud.domain.provisioning.remote;

import java.util.List;
import java.util.Map;

/** VM cluster 노드 정보 + SSH 접속 자료 노출. */
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

    /**
     * @param sshJump 노드가 사설망이라 점프 호스트를 거쳐야 하면 {@code user@host[:port]}. 없으면 null.
     *     비밀번호는 담지 않는다 — 화면이 명령을 만들 때 필요한 것은 주소뿐이다
     */
    record NodeListResult(
            String clusterName, String provider, String sshUser, String sshJump, List<Map<String, Object>> nodes) {}

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
