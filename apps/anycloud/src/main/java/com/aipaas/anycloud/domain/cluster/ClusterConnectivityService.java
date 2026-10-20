package com.aipaas.anycloud.domain.cluster;

import java.util.concurrent.CompletableFuture;

/**
 * cluster 연결 상태와 status 동기화. 판정 근거는 agent heartbeat 단일.
 *
 * <p>Impl: {@link com.aipaas.anycloud.domain.cluster.internal.ClusterConnectivityServiceImpl}.
 */
public interface ClusterConnectivityService {

    /**
     * Cluster 연결 가능 여부: agent 의 fresh heartbeat 유무.
     *
     * @return true = reachable, false = unreachable (예외 throw 안 함, 단 cluster 가 없으면
     *         {@link com.aipaas.anycloud.common.error.exception.ClusterNotFoundException}).
     */
    boolean testClusterConnection(String clusterName);

    /**
     * Cluster 의 K8s version + status 를 동기 업데이트.
     *
     * <p>agent 의 fresh k8sVersion 이 없으면 UNKNOWN + INACTIVE.
     */
    void updateClusterVersionAndStatus(ClusterEntity clusterEntity);

    /** {@link #updateClusterVersionAndStatus} 의 비동기 변형. KUBERNETES_EXECUTOR 위에서 실행. */
    CompletableFuture<Void> updateClusterVersionAndStatusAsync(ClusterEntity clusterEntity);

    /** 모든 등록 cluster 의 status 를 순차 업데이트 (scheduler-style). */
    void updateAllClusterStatuses();
}
