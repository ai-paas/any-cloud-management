package com.aipaas.anycloud.domain.helmrepo.mapper;

import com.aipaas.anycloud.domain.helmrepo.HelmRepoEntity;
import com.aipaas.anycloud.domain.helmrepo.model.HelmRepo;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * HelmRepoEntity ↔ HelmRepo 도메인 변환 boundary.
 *
 * <p>커스텀 룰:
 * <ul>
 *   <li>{@code insecureSkipTlsVerify} 는 entity 가 null 일 때 domain false 로 변환 — MapStruct 가
 *       Boolean → boolean primitive 처리 시 자동 처리 안 됨. {@link #normalizeFlag(Boolean)} default
 *       method 로 처리.</li>
 *   <li>{@code createdAt} / {@code updatedAt} 은 domain → entity 시 무시 (JPA lifecycle callback
 *       이 관리). {@code @Mapping(ignore = true)} 명시.</li>
 * </ul>
 */
@Mapper(componentModel = "spring")
public interface HelmRepoMapper {

    HelmRepo toDomain(HelmRepoEntity entity);

    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "insecureSkipTlsVerify", expression = "java(normalizeFlag(domain.insecureSkipTlsVerify()))")
    HelmRepoEntity toEntity(HelmRepo domain);

    /** Domain → Entity 시 기존 entity 필드를 갱신 (id / createdAt 보존). controller 의 patch flow 용. */
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "insecureSkipTlsVerify", expression = "java(normalizeFlag(domain.insecureSkipTlsVerify()))")
    void update(HelmRepo domain, @MappingTarget HelmRepoEntity entity);

    /** Null → false 변환. 기존 {@code Boolean.TRUE.equals(...)} 동일 의미. */
    default boolean normalizeFlag(Boolean v) {
        return Boolean.TRUE.equals(v);
    }
}
