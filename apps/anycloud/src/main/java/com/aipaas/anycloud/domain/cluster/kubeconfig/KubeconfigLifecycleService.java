package com.aipaas.anycloud.domain.cluster.kubeconfig;

/**
 * cluster_agent.status = ACTIVE 시 ClusterEntity 의 자격증명 컬럼 cleanup.
 *
 * <p>지울 컬럼이 더 이상 없다. serverCa, clientCa, clientKey, clientToken 이 모두 DROP 돼
 * 구현은 no-op 이다. 인터페이스는 호출부 정리 전까지 남겨 둔다.
 */
public interface KubeconfigLifecycleService {

    /**
     * Agent 가 ACTIVE 로 전환된 cluster 의 kubeconfig 필드 cleanup.
     *
     * <p>Flag off 또는 cluster 미발견 시 no-op. 부분 cleanup 도 가능 (이미 NULL 인 필드는 그대로).
     *
     * @return true 면 cleanup 수행, false 면 skip (flag off / cluster 미존재 / 이미 cleared)
     */
    boolean maybeCleanupOnActive(String clusterName);
}
