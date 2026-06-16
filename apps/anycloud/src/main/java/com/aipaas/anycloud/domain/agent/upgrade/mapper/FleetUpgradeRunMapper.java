package com.aipaas.anycloud.domain.agent.upgrade.mapper;

import com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeRun;
import com.aipaas.anycloud.domain.agent.upgrade.FleetUpgradeRunEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * FleetUpgradeRunEntity ↔ FleetUpgradeRun 도메인 변환 boundary.
 *
 * <p>16 field 1:1 매핑. {@code createdAt} 은 @CreationTimestamp 가 채우므로 ignore.
 */
@Mapper(componentModel = "spring")
public interface FleetUpgradeRunMapper {

    FleetUpgradeRun toDomain(FleetUpgradeRunEntity entity);

    @Mapping(target = "createdAt", ignore = true)
    FleetUpgradeRunEntity toEntity(FleetUpgradeRun domain);
}
