package com.aipaas.anycloud.domain.cluster.backup;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * {@link BackupHistoryEntity} 영속화 port.
 *
 * <p>BackupHistoryWriter SPI 의 anycloud 구현체 ({@link JpaBackupHistoryWriter}) 가 사용.
 */
@Repository
public interface BackupHistoryRepository extends JpaRepository<BackupHistoryEntity, String> {

    /** 한 cluster 의 최근 backup 이력 (UI 표시 — pageable 로 페이징). */
    List<BackupHistoryEntity> findByClusterIdOrderByStartedAtDesc(String clusterId, Pageable pageable);

    /** cluster + type 별 최신 1건 — "이 cluster 의 가장 최근 etcd backup 은?" 답변. */
    Optional<BackupHistoryEntity> findFirstByClusterIdAndBackupTypeOrderByStartedAtDesc(
            String clusterId, String backupType);
}
