package com.aipaas.anycloud.domain.cluster;

import com.aipaas.anycloud.domain.cluster.api.request.CreateClusterDto;
import com.aipaas.anycloud.domain.cluster.api.request.UpdateClusterDto;
import com.aipaas.anycloud.domain.cluster.model.Cluster;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

/**
 * <pre>
 * ClassName : ClusterService
 * Type : interface
 * Description : 쿠버네티스 클러스터와 관련된 함수를 정리한 인터페이스입니다.
 * Related : ClusterServiceImpl
 * </pre>
 */
public interface ClusterService {

    List<ClusterEntity> getClusterEntities();

    /**
     * 특정 status 의 cluster 만 fetch — JPA DB-side filter. fleet 작업이 ACTIVE 만 처리하는 경우
     * 등에서 전체 row hydration 비용 회피.
     */
    List<ClusterEntity> getClusterEntitiesByStatus(com.aipaas.anycloud.domain.cluster.model.ClusterStatus status);

    ClusterEntity getClusterEntity(String clusterName);

    /**
     * Step 2 (Entity → Domain) — JPA-free immutable view. 새 consumer 는 본 메서드를 사용하고,
     * 기존 {@link #getClusterEntity(String)} 는 점진 deprecate.
     *
     * <p>자세한 로드맵: {@code docs/architecture/design/domain-model-roadmap.md}.
     */
    Optional<Cluster> findDomainById(String clusterName);

    /** {@link #getClusterEntities()} 의 domain 변형. */
    List<Cluster> findAllDomain();

    /**
     * Paged variant — 1000+ cluster 환경의 heap pressure 회피용.
     *
     * <p>{@link #findAllDomain()} 는 전체 로드라 admin 도구 / 작은 환경에서만 안전. 신규 사용처는
     * 본 메서드 사용 권장. 기존 호출자는 점진 마이그레이션 (ClusterFacadeImpl 의 list/getOne
     * 가 우선 후보).
     *
     * @param pageable {@code PageRequest.of(page, size)} — 기본 limit 권장 100.
     * @return 도메인 record 의 Spring Data Page.
     */
    Page<Cluster> findAllDomain(Pageable pageable);

    HttpStatus createCluster(CreateClusterDto cluster);

    HttpStatus updateCluster(String clusterName, UpdateClusterDto cluster);

    HttpStatus deleteCluster(String clusterName);

    Boolean testClusterConnection(String clusterName);

    HttpStatus refreshClusterStatus(String clusterName);

    void updateAllClusterStatuses();
}
