package com.aipaas.anycloud.domain.cluster;

/** Node debug shell 위한 임시 priviledged pod 생성. */
public interface NodeDebugPodService {

    DebugPodResult create(String clusterName, CreateRequest request);

    /**
     * @param toolsShell 호스트가 아니라 클러스터를 보는 셸. kubectl, k9s 가 이미지에서 온다.
     * @param serviceAccount toolsShell 이 클러스터를 볼 자격. 없으면 kubectl 이 forbidden 을 받는다.
     */
    record CreateRequest(
            String nodeName,
            String namespace,
            String image,
            String podName,
            Long ttlSeconds,
            boolean toolsShell,
            String serviceAccount) {}

    record DebugPodResult(String clusterName, String nodeName, String namespace, String podName, String expiresAt) {}

    class NodeDebugPodException extends RuntimeException {
        private final String errorCode;

        public NodeDebugPodException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public NodeDebugPodException(String errorCode, String message, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
