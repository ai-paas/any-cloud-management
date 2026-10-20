package com.aipaas.anycloud.domain.kube;

import io.aipaas.cluster.agent.runtime.ResolvedResource;

/**
 * cluster 의 kind metadata(plural, namespaced 여부, group, version)를 agent 에 조회해 캐시.
 * 목록을 코드에 고정하면 CRD 추가 시마다 수동 갱신 필요.
 *
 * <p>캐시 대상은 kind 스키마 한정. pod, deployment 의 실제 값은 캐시 없이 매번 agent 경유 조회.
 *
 * <p>캐시 TTL 30분. addon 설치 직후 {@link #invalidate(String)} 자동 호출로 신규 CRD 반영.
 * 운영자는 {@code POST /v1/admin/clusters/{c}/kind-cache/flush} 로 직접 비우기 가능.
 *
 * <p>agent 미도달 시 코드 내 cluster-scoped 목록으로 namespaced 여부만 판정. plural 과 group
 * 은 입력값 그대로 사용.
 */
public interface KindResolver {

    /**
     * cluster 의 kind 입력 (plural / short / Kind / kind.group.version) 을 정규화.
     *
     * @param clusterName 대상 cluster id
     * @param kindOrPluralOrShort 사용자 입력 (예: "pods", "po", "Pod", "deployments.apps")
     * @return 정규화된 ResolvedResource. agent 도 못 잡으면 hardcoded fallback 의 best-effort.
     *         {@code null} 반환 안함. 미지 kind 도 namespaced=true 가정의 placeholder 반환.
     */
    ResolvedResource resolve(String clusterName, String kindOrPluralOrShort);

    /** 해당 cluster 의 kind cache 전체 무효화. 새 CRD install 직후 호출 권장. */
    void invalidate(String clusterName);

    /** 모든 cluster cache flush: admin / boot-time. */
    void invalidateAll();
}
