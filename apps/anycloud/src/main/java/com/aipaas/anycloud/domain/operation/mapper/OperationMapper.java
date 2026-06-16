package com.aipaas.anycloud.domain.operation.mapper;

import com.aipaas.anycloud.domain.operation.Operation;
import com.aipaas.anycloud.domain.operation.OperationEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * JPA Entity ↔ Domain 변환 boundary.
 *
 * <p>Hexagonal pattern 의 adapter 책임 — service 내부에서만 사용. Controller / domain 코드는 이
 * mapper 를 직접 호출하지 않는다 (Service interface 가 domain 만 노출하는 방향으로 점진 migration).
 */
@Mapper(componentModel = "spring")
public interface OperationMapper {

    Operation toDomain(OperationEntity entity);

    /**
     * createdAt / updatedAt 은 JPA {@code @CreationTimestamp / @UpdateTimestamp} 가 관리하므로
     * domain → entity 변환에서 제외.
     */
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    OperationEntity toEntity(Operation domain);
}
