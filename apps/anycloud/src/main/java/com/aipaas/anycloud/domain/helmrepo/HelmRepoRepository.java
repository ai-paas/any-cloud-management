package com.aipaas.anycloud.domain.helmrepo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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

    /**
     * source 별 개수. metrics 갱신이 30초마다 전체 row 를 읽어 개수만 세던 것을 DB 집계로 옮긴다.
     * repo 수가 늘어도 전송량이 고정된다.
     *
     * @return {@code [source, count]} 행 목록. row 가 없는 source 는 포함되지 않는다
     */
    @Query("select r.source, count(r) from HelmRepoEntity r group by r.source")
    List<Object[]> countGroupedBySource();

    boolean existsByName(String name);
}
