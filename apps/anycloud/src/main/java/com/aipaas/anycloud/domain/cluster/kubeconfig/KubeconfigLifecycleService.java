package com.aipaas.anycloud.domain.cluster.kubeconfig;

/**
 * cluster_agent.status = ACTIVE 시 ClusterEntity 의 kubeconfig 필드 cleanup.
 *
 * <p>Agent path 가 안정적으로 동작하면 fabric8 fallback 의 kubeconfig 보관 의미가 약해짐.
 * 옵트인 flag {@code agent.kubeconfig.cleanup-on-active} (default false) 로 enable —
 * 운영 안정성 검증 후 점진 활성화.
 *
 * <p>Cleanup 대상 필드 (모두 ClusterEntity 의 sensitive 인증 정보):
 * <ul>
 *   <li>serverCa — Cluster CA cert</li>
 *   <li>clientCa — mTLS client cert</li>
 *   <li>clientKey — mTLS client private key</li>
 *   <li>clientToken — bearer token</li>
 * </ul>
 *
 * <p>{@code apiServerUrl} 은 유지 — UI 표시 / agent 접속 endpoint 용. PII 아님.
 *
 * <p>주의: Cleanup 후 agent 가 죽으면 fabric8 fallback path 가 인증 실패.
 * (agent 통해 kubeconfig 재발급) 이 완성되면 그 path 로 복구 가능.
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
