package com.aipaas.anycloud.domain.kube;

import io.aipaas.cluster.agent.runtime.ResolvedResource;

/**
 * cluster 의 kind metadata(plural, namespaced 여부, group, version)를 agent 에 물어 캐시합니다.
 * 목록을 코드에 박아 두면 CRD 가 추가될 때마다 손으로 맞춰야 하기 때문입니다.
 *
 * <p>캐시하는 건 kind 의 스키마뿐입니다. pod 나 deployment 의 실제 값은 캐시하지 않고 매번
 * agent 를 거쳐 조회합니다. 화면에 보이는 데이터는 항상 최신입니다.
 *
 * <p>캐시는 30분 TTL 로 만료되고, addon 설치 직후 {@link #invalidate(String)} 가 자동으로
 * 불립니다. 새 CRD 가 그때 들어오기 때문입니다. 운영자는
 * {@code POST /v1/admin/clusters/{c}/kind-cache/flush} 로 직접 비울 수 있습니다.
 *
 * <p>agent 가 닿지 않으면 코드에 있는 cluster-scoped 목록으로 namespaced 여부만 판단합니다.
 * plural 과 group 은 입력값을 그대로 씁니다.
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
