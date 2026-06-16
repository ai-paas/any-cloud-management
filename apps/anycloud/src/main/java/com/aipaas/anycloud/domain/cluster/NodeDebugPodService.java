package com.aipaas.anycloud.domain.cluster;

/**
 * Node debug shell 위한 임시 priviledged pod 생성.
 *
 * <p>kubectl debug node 등가. host PID/Net/IPC namespace + privileged 의 nsenter pod 을 생성.
 * 사용자는 반환된 (namespace, pod_name) 으로 기존 PodExec WebSocket 으로 연결 — 새 RPC 추가 없이
 * 재사용.
 *
 * <p>Cleanup: 운영자 책임. TTL annotation 만 부여 — 미래 sweeper job 이 cleanup 가능.
 */
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
