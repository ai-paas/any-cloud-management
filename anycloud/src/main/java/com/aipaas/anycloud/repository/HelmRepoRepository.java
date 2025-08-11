package com.aipaas.anycloud.repository;

import com.aipaas.anycloud.model.entity.ClusterEntity;
import com.aipaas.anycloud.model.entity.HelmRepoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * <pre>
 * ClassName : HelmRepoRepository
 * Type : interface
 * Description : HelmRepo JPA 구현을 위한 인터페이스입니다.
 * Related : spring-boot-starter-data-jpa
 * </pre>
 */
@Repository
public interface HelmRepoRepository extends JpaRepository<HelmRepoEntity, Integer> {

	Optional<HelmRepoEntity> findByName(String name);

	boolean existsByName(String name);


}
