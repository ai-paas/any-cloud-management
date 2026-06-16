package com.aipaas.anycloud.domain.provisioning;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowMessageLogRepository extends JpaRepository<WorkflowMessageLogEntity, String> {

    List<WorkflowMessageLogEntity> findByVmClusterIdOrderByCreatedAtDesc(String vmClusterId);

    List<WorkflowMessageLogEntity> findByClusterNameOrderByCreatedAtDesc(String clusterName);

    @Query("select count(l) > 0 from WorkflowMessageLogEntity l where l.messageId = :messageId")
    boolean existsByMessageId(@Param("messageId") String messageId);

    @Query("select l from WorkflowMessageLogEntity l "
            + "where l.result = com.aipaas.anycloud.domain.provisioning.model.WorkflowMessageLogResult.FAILED "
            + "  and (:clusterName is null or l.clusterName = :clusterName) "
            + "order by l.createdAt desc")
    List<WorkflowMessageLogEntity> findFailed(
            @Param("clusterName") String clusterName, org.springframework.data.domain.Pageable pageable);
}
