package com.aipaas.anycloud.repository;

import com.aipaas.anycloud.model.entity.ClusterEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * <pre>
 * ClassName : ClusterRepository
 * Type : interface
 * Description : Mec JPA 구현을 위한 인터페이스입니다.
 * Related : spring-boot-starter-data-jpa
 * </pre>
 */
@Repository
public interface ClusterRepository extends JpaRepository<ClusterEntity, Integer> {

	@Query("select c from ClusterEntity c WHERE c.id = ?1")
	Optional<ClusterEntity> findByName(String name);

}
