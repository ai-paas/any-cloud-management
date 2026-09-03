package com.aipaas.anycloud.domain.kube;

import io.aipaas.cluster.agent.runtime.ResolvedResource;

/**
 * Cluster 의 K8s kind metadata (plural / namespaced / group / version) lookup.
 *
 * <p>hardcoded {@code K8sKinds.CLUSTER_SCOPED} set 의 drift 문제
 * (CRD 추가 시 수동 sync 필요) 를 해결하기 위해 도입. 내부적으로 agent 의
 * {@code RESOLVE_RESOURCE} RPC + Caffeine cache (TTL 30분) 사용.
 *
 * <h2>캐시 대상 명확화</h2>
 * <ul>
 *   <li><strong>Cache 됨</strong>: kind metadata (schema) — namespaced 여부, plural, group, version.
 *       cluster 수명 동안 거의 불변 (CRD 신규 install 외에는 변경 없음).</li>
 *   <li><strong>Cache 안 됨</strong>: 실제 resource data (pods/deployments/... 값) — 매 호출
 *       agent → K8s API 직접 조회. 사용자 입장에서 모든 데이터는 항상 fresh.</li>
 * </ul>
 *
 * <h2>호출 흐름</h2>
 * <pre>{@code
 * 1. KindResolver.resolve(cluster, "pods")
 *    ├─ cache hit → 즉시 ResolvedResource{namespaced=true, plural=pods}
 *    └─ cache miss → agent RESOLVE_RESOURCE → 결과 캐시 후 반환
 * 2. effectiveNamespace 결정 (namespaced 면 namespace 그대로, 아니면 null)
 * 3. kubeService.getResource(...) — fresh data, no cache
 * }</pre>
 *
 * <h2>Invalidation</h2>
 * <ul>
 *   <li>TTL 30분 자동 만료</li>
 *   <li>Addon install 직후 {@link #invalidate(String)} 자동 호출 (새 CRD 도입 시점)</li>
 *   <li>Admin endpoint {@code POST /admin/clusters/{c}/kind-cache:flush}</li>
 * </ul>
 *
 * <h2>Fallback</h2>
 * agent unavailable / RESOLVE 실패 시 hardcoded {@code K8sKinds.CLUSTER_SCOPED} set 으로 best-effort
 * (namespaced=false 만 결정, plural/group/version 은 입력 그대로). defense-in-depth.
 */
public interface KindResolver {

    /**
     * cluster 의 kind 입력 (plural / short / Kind / kind.group.version) 을 정규화.
     *
     * @param clusterName 대상 cluster id
     * @param kindOrPluralOrShort 사용자 입력 (예: "pods", "po", "Pod", "deployments.apps")
     * @return 정규화된 ResolvedResource. agent 도 못 잡으면 hardcoded fallback 의 best-effort.
     *         {@code null} 반환 안함 — 미지 kind 도 namespaced=true 가정의 placeholder 반환.
     */
    ResolvedResource resolve(String clusterName, String kindOrPluralOrShort);

    /** 해당 cluster 의 kind cache 전체 무효화. 새 CRD install 직후 호출 권장. */
    void invalidate(String clusterName);

    /** 모든 cluster cache flush — admin / boot-time. */
    void invalidateAll();
}
