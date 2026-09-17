package com.aipaas.anycloud.domain.provisioning;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowMessageLogRepository extends JpaRepository<WorkflowMessageLogEntity, String> {

    List<WorkflowMessageLogEntity> findByVmClusterIdOrderByCreatedAtDesc(String vmClusterId);

    List<WorkflowMessageLogEntity> findByClusterNameOrderByCreatedAtDesc(String clusterName);

    @Query("select count(l) > 0 from WorkflowMessageLogEntity l where l.messageId = :messageId")
    boolean existsByMessageId(@Param("messageId") String messageId);

    /**
     * 보관 기간이 지난 행을 지운다.
     *
     * <p>{@code startedAt} 이 아니라 {@code createdAt} 기준이다 — startedAt 은 메시지가 만들어진
     * 시각이라 재전달이면 과거로 찍힌다.
     */
    @Modifying
    @Query("delete from WorkflowMessageLogEntity l where l.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") java.time.LocalDateTime cutoff);

    @Query("select l from WorkflowMessageLogEntity l "
            + "where l.result = com.aipaas.anycloud.domain.provisioning.model.WorkflowMessageLogResult.FAILED "
            + "  and (:clusterName is null or l.clusterName = :clusterName) "
            + "order by l.createdAt desc")
    List<WorkflowMessageLogEntity> findFailed(
            @Param("clusterName") String clusterName, org.springframework.data.domain.Pageable pageable);
}
