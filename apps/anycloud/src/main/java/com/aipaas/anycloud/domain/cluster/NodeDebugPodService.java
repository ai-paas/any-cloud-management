package com.aipaas.anycloud.domain.cluster;

/** Node debug shell 위한 임시 priviledged pod 생성. */
public interface NodeDebugPodService {

    DebugPodResult create(String clusterName, CreateRequest request);

    /**
     * @param toolsShell 호스트가 아닌 클러스터 대상 셸. kubectl, k9s 는 이미지 제공
     * @param serviceAccount toolsShell 의 cluster 조회 자격. 부재 시 kubectl 호출이 forbidden
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
