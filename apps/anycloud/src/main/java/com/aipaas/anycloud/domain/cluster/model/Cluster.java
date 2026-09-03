package com.aipaas.anycloud.domain.cluster.model;

import java.time.ZonedDateTime;

/**
 * Registered cluster 의 immutable 도메인 표현.
 *
 * <p>JPA / persistence 와 분리된 순수 자바 record. {@code @Entity}, {@code @Column} 등 어떤
 * infrastructure 어노테이션도 참조하지 않는다. 도메인 로직과 테스트는 이 타입만으로 동작 가능.
 *
 * <p>도메인 ↔ JPA 변환은 {@link com.aipaas.anycloud.domain.cluster.mapper.ClusterMapper} 가 단방향
 * boundary 에서 처리한다 (Hexagonal pattern 의 adapter 경계).
 *
 * <p>K8s admin 자격 (apiServerUrl/IP, server/client CA, client key/token) 과
 * monitServerUrl 모두 제거 — cluster-agent 가 in-cluster 에서 K8s API + Prometheus discover 대행.
 * Backend 는 cluster 자체에 직접 dial 하지 않음. ArgoCD/Flux/OCM 표준 pull-based pattern.
 *
 * @param id                  cluster id (= 사용자 지정 name). 변경 불가.
 * @param description         사용자 지정 설명.
 * @param status              status text (예: ACTIVE, PENDING_AGENT, INACTIVE).
 * @param version             K8s server version (예: v1.33.9). agent dial-in 시 backfill.
 * @param clusterType         "Public" / "Private" 등.
 * @param clusterProvider     cluster 가 운영되는 cloud / on-prem 표식 (aws, gcp, azure, orb 등).
 * @param provisioningType    "registered" / "vm" — registration source.
 * @param provisioningStatus  registered cluster 도 lifecycle 추적 (READY / FAILED / ...).
 * @param hasGpuNodes         GPU 노드 존재 여부 — dcgm-exporter 자동 설치 트리거.
 * @param stackName           pulumi stack name (VM provisioned cluster 만 의미).
 * @param createdAt           JPA-managed.
 * @param updatedAt           JPA-managed.
 */
public record Cluster(
        String id,
        String description,
        String status,
        String version,
        String clusterType,
        String clusterProvider,
        String provisioningType,
        String provisioningStatus,
        Boolean hasGpuNodes,
        String stackName,
        ZonedDateTime createdAt,
        ZonedDateTime updatedAt) {

    /** VM provisioned cluster 여부. */
    public boolean isVmProvisioned() {
        return "vm".equalsIgnoreCase(provisioningType);
    }

    /** Active 상태 — UI/배포 가능한 정상 상태. */
    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(status);
    }

    /**
     * Agent dial-in 대기 상태 — registered cluster 가 등록 직후 agent 가 아직 cluster 에서 dial 하지
     * 않은 placeholder 상태. agent 가 붙으면 ACTIVE 로 전환. 도메인에서는 status 문자열만으로 판단.
     */
    public boolean isPendingAgent() {
        return "PENDING_AGENT".equalsIgnoreCase(status);
    }
}
