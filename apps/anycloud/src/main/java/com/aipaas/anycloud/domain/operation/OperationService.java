package com.aipaas.anycloud.domain.operation;

import com.aipaas.anycloud.domain.operation.model.OperationState;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import java.util.List;
import java.util.Optional;

/** Long-Running Operation 의 lifecycle 관리. */
public interface OperationService {

    /** 새 operation 등록 (state=PENDING). */
    OperationEntity start(
            OperationType type, String resourceType, String resourceId, String requestPayload, int totalSteps);

    /** PENDING → RUNNING + 시작시각 set. */
    OperationEntity markRunning(String operationId);

    /** 진행률/단계 갱신. percent 는 0..100. */
    OperationEntity updateProgress(String operationId, String currentStep, Integer stepIndex, Integer percent);

    OperationEntity complete(String operationId, String resultPayload);

    OperationEntity fail(String operationId, String errorMessage);

    OperationEntity cancel(String operationId);

    Optional<OperationEntity> findById(String operationId);

    /** Step 2 (Entity → Domain) pilot — JPA-free immutable view. */
    Optional<Operation> findDomainById(String operationId);

    /**
     * 해당 리소스의 가장 최근 non-terminal (PENDING/RUNNING) operation 을 반환.
     * Workflow step 핸들러가 진행 상태를 push 할 대상을 식별하는 데 사용.
     */
    Optional<OperationEntity> findLatestActiveByResource(String resourceType, String resourceId);

    List<OperationEntity> listByResource(String resourceType, String resourceId, int limit);

    List<OperationEntity> search(
            OperationState state, OperationType type, String resourceType, String resourceId, int limit);
}
