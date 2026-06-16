package com.aipaas.anycloud.domain.provisioning;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * VmCluster state history persistence port.
 */
@Repository
public interface VmClusterStateHistoryRepository extends JpaRepository<VmClusterStateHistoryEntity, String> {

    /** 특정 cluster 의 transition history — 최신 → 과거 순. */
    List<VmClusterStateHistoryEntity> findByClusterNameOrderByCreatedAtDesc(String clusterName, Pageable pageable);

    /**
     * Retention cleanup — 지정 시각 이전의 row 일괄 삭제. state history 무한 증가 방지.
     */
    @Modifying
    @Query("DELETE FROM VmClusterStateHistoryEntity h WHERE h.createdAt < :before")
    int deleteByCreatedAtBefore(@Param("before") LocalDateTime before);
}
