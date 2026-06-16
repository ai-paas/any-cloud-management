package com.aipaas.anycloud.domain.cluster.mapper;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.model.Cluster;
import com.aipaas.anycloud.domain.cluster.model.ClusterStatus;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * JPA Entity ↔ Domain 변환 boundary.
 *
 * <p>Hexagonal pattern 의 adapter 책임 — service 내부에서만 사용. Controller / domain 코드는 이
 * mapper 를 직접 호출하지 않는다 (Service interface 가 점진적으로 domain 만 노출하는 방향으로
 * migration).
 *
 * <p>ClusterStatus enum ↔ String 변환은 default method 로 처리 (entity.status enum →
 * domain.status string / domain.status string → entity.status enum via fromOrUnknown).
 */
@Mapper(componentModel = "spring")
public interface ClusterMapper {

    @Mapping(target = "status", source = "status", qualifiedByName = "statusToName")
    Cluster toDomain(ClusterEntity entity);

    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "status", source = "status", qualifiedByName = "statusFromName")
    @Mapping(target = "hasGpuNodes", source = "hasGpuNodes", qualifiedByName = "normalizeGpuFlag")
    ClusterEntity toEntity(Cluster domain);

    /** Entity → Domain: ClusterStatus enum → String (name). null 은 그대로 유지. */
    @Named("statusToName")
    default String statusToName(ClusterStatus status) {
        return status == null ? null : status.name();
    }

    /** Domain → Entity: String → ClusterStatus. {@link ClusterStatus#fromOrUnknown} 가 매핑 + UNKNOWN fallback. */
    @Named("statusFromName")
    default ClusterStatus statusFromName(String name) {
        return ClusterStatus.fromOrUnknown(name);
    }

    /** null Boolean → false. 기존 {@code v != null && v} 동일 의미. */
    @Named("normalizeGpuFlag")
    default boolean normalizeGpuFlag(Boolean v) {
        return Boolean.TRUE.equals(v);
    }
}
