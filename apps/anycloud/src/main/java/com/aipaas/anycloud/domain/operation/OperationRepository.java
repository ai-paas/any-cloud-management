package com.aipaas.anycloud.domain.operation;

import com.aipaas.anycloud.domain.operation.model.OperationState;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperationRepository extends JpaRepository<OperationEntity, String> {

    List<OperationEntity> findByResourceTypeAndResourceIdOrderByCreatedAtDesc(
            String resourceType, String resourceId, Pageable pageable);

    @Query("SELECT o FROM OperationEntity o "
            + "WHERE (:state IS NULL OR o.state = :state) "
            + "  AND (:type IS NULL OR o.type = :type) "
            + "  AND (:resourceType IS NULL OR o.resourceType = :resourceType) "
            + "  AND (:resourceId IS NULL OR o.resourceId = :resourceId) "
            + "ORDER BY o.createdAt DESC")
    List<OperationEntity> search(
            @Param("state") OperationState state,
            @Param("type") OperationType type,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            Pageable pageable);

    /**
     * 완료 (SUCCEEDED / FAILED / CANCELLED) 된 operation 중 createdAt 가 cutoff 이전인 row 삭제.
     * 한 번에 너무 많이 지우지 않도록 caller 가 batch limit 적용 권장 (Pageable + repeated calls).
     *
     * @return 삭제된 행 수
     */
    @Modifying
    @Query("DELETE FROM OperationEntity o "
            + "WHERE o.state IN (com.aipaas.anycloud.domain.operation.model.OperationState.SUCCEEDED, "
            + "                   com.aipaas.anycloud.domain.operation.model.OperationState.FAILED, "
            + "                   com.aipaas.anycloud.domain.operation.model.OperationState.CANCELLED) "
            + "  AND o.createdAt < :cutoff")
    int deleteCompletedBefore(@Param("cutoff") LocalDateTime cutoff);
}
