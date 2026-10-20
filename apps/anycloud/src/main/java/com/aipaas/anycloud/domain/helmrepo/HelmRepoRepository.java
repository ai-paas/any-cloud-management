package com.aipaas.anycloud.domain.helmrepo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** HelmRepo JPA 리포지터리. */
@Repository
public interface HelmRepoRepository extends JpaRepository<HelmRepoEntity, String> {

    Optional<HelmRepoEntity> findByName(String name);

    /**
     * source 별 개수. metrics 갱신의 30초 주기 전체 row 조회를 DB 집계로 대체. repo 수 증가와
     * 무관하게 전송량 고정.
     *
     * @return {@code [source, count]} 행 목록. row 가 없는 source 는 미포함
     */
    @Query("select r.source, count(r) from HelmRepoEntity r group by r.source")
    List<Object[]> countGroupedBySource();

    boolean existsByName(String name);
}
