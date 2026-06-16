package com.aipaas.anycloud.domain.provisioning.mapper;

import com.aipaas.anycloud.domain.provisioning.VmClusterStateHistoryEntity;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStateHistory;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * VmClusterStateHistoryEntity ↔ VmClusterStateHistory 도메인 변환 boundary.
 *
 * <p>{@code valid} 가 null 인 경우 Boolean.TRUE 기본값을 부여하는 룰은 {@link #normalizeValid}
 * default method 로 처리.
 */
@Mapper(componentModel = "spring")
public interface VmClusterStateHistoryMapper {

    VmClusterStateHistory toDomain(VmClusterStateHistoryEntity entity);

    /**
     * {@code createdAt} 은 {@code @CreationTimestamp} 가 채우므로 ignore. {@code valid} 는
     * domain 이 null 일 때 entity 에는 TRUE 로 기본 (state machine 기본 동작).
     */
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "valid", expression = "java(normalizeValid(domain.valid()))")
    VmClusterStateHistoryEntity toEntity(VmClusterStateHistory domain);

    /** null → TRUE 변환. 기존 {@code v == null ? Boolean.TRUE : v} 동일. */
    default Boolean normalizeValid(Boolean v) {
        return v == null ? Boolean.TRUE : v;
    }
}
