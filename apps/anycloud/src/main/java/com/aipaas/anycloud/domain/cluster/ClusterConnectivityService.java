package com.aipaas.anycloud.domain.cluster;

import java.util.concurrent.CompletableFuture;

/**
 * cluster 의 connectivity / status sync 책임 — agent path 우선, fabric8 ping fallback.
 *
 * <p>Impl: {@link com.aipaas.anycloud.domain.cluster.internal.ClusterConnectivityServiceImpl}.
 */
public interface ClusterConnectivityService {

    /**
     * Cluster 연결 가능 여부 — agent path 우선 (fresh heartbeat), 실패 시 fabric8 ping fallback.
     *
     * @return true = reachable, false = unreachable (예외 throw 안 함, 단 cluster 미존재 시
     *         {@link com.aipaas.anycloud.common.error.exception.ClusterNotFoundException}).
     */
    boolean testClusterConnection(String clusterName);

    /**
     * Cluster 의 K8s version + status 를 동기 업데이트.
     *
     * <p>우선순위: (1) agent 의 fresh k8sVersion → (2) fabric8 직접 조회 → (3) UNKNOWN + INACTIVE.
     */
    void updateClusterVersionAndStatus(ClusterEntity clusterEntity);

    /** {@link #updateClusterVersionAndStatus} 의 비동기 변형. KUBERNETES_EXECUTOR 위에서 실행. */
    CompletableFuture<Void> updateClusterVersionAndStatusAsync(ClusterEntity clusterEntity);

    /** 모든 등록 cluster 의 status 를 순차 업데이트 (scheduler-style). */
    void updateAllClusterStatuses();
}
