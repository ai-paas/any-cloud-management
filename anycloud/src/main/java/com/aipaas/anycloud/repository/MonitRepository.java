package com.aipaas.anycloud.repository;

import com.aipaas.anycloud.model.entity.MonitEntity;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * <pre>
 * ClassName : MHRepository
 * Type : interface
 * Description : MH JPA 구현을 위한 인터페이스입니다.
 * Related : spring-boot-starter-data-jpa, MHServiceImpl, AuthServiceImpl
 * </pre>
 */
@Repository
public interface MonitRepository {

	Optional<MonitEntity> currentMetrics();
}
