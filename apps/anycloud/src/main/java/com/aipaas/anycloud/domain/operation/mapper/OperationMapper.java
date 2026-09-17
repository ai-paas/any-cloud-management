package com.aipaas.anycloud.domain.operation.mapper;

import com.aipaas.anycloud.domain.operation.Operation;
import com.aipaas.anycloud.domain.operation.OperationEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** JPA Entity ↔ Domain 변환 boundary. */
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
