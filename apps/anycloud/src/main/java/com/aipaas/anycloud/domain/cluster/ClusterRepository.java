package com.aipaas.anycloud.domain.cluster;

import com.aipaas.anycloud.domain.cluster.model.ClusterStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
public interface ClusterRepository extends JpaRepository<ClusterEntity, String> {

    Optional<ClusterEntity> findById(String id);

    /**
     * 특정 status 의 cluster 만 DB 에서 직접 fetch. 호출자가 {@code findAll().stream().filter(...)}
     * 로 in-memory 필터링하던 핫스팟 (#8) 대체. ACTIVE 만 broadcast 하는 경우 등.
     *
     * <p>1000+ cluster 환경에서 heap pressure / GC pause 완화.
     */
    List<ClusterEntity> findAllByStatus(ClusterStatus status);
}
