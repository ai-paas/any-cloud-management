package com.aipaas.anycloud.domain.helmrepo;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * <pre>
 * ClassName : HelmRepoRepository
 * Type : interface
 * Description : HelmRepo JPA 구현을 위한 인터페이스입니다.
 * Related : spring-boot-starter-data-jpa
 * </pre>
 */
@Repository
public interface HelmRepoRepository extends JpaRepository<HelmRepoEntity, String> {

    Optional<HelmRepoEntity> findByName(String name);

    boolean existsByName(String name);
}
