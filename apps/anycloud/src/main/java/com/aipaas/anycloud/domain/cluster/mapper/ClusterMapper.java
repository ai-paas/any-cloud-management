package com.aipaas.anycloud.domain.cluster.mapper;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.model.Cluster;
import com.aipaas.anycloud.domain.cluster.model.ClusterStatus;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/** JPA Entity ↔ Domain 변환 boundary. */
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
