package com.aipaas.anycloud.domain.cluster;

/** Node debug shell 위한 임시 priviledged pod 생성. */
public interface NodeDebugPodService {

    DebugPodResult create(String clusterName, CreateRequest request);

    record CreateRequest(String nodeName, String namespace, String image, String podName, Long ttlSeconds) {}

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
