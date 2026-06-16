package com.aipaas.anycloud.domain.operation;

import com.aipaas.anycloud.domain.operation.model.OperationState;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import java.time.LocalDateTime;

/**
 * Long-Running Operation 의 immutable 도메인 표현.
 *
 * <p>JPA / persistence 와 분리된 순수 자바 record. infrastructure 어노테이션 (@Entity, @Column 등) 을
 * 일절 참조하지 않는다 — 도메인 로직과 테스트는 이 타입만으로 동작 가능하다.
 *
 * <p>도메인 ↔ JPA 변환은 {@link com.aipaas.anycloud.domain.operation.mapper.OperationMapper} 가 단방향
 * boundary 에서 처리한다 (Hexagonal pattern 의 adapter 경계).
 *
 * <p>Step 2 pilot — 다른 entity (Cluster, VmCluster 등) 의 domain 추출은 동일한 패턴을 따르되
 * incremental 하게 진행한다. 자세한 로드맵: docs/architecture/design/domain-model-roadmap.md.
 *
 * @param id            UUID. JPA insert 전에는 비어있을 수 있음 — start() 에서 채워짐.
 * @param type          Operation 종류 (CLUSTER_CREATE / VM_CLUSTER_DELETE 등).
 * @param resourceType  대상 리소스 타입 (예: "cluster", "vm-cluster").
 * @param resourceId    대상 리소스 식별자.
 * @param state         PENDING → RUNNING → SUCCEEDED | FAILED | CANCELLED.
 * @param currentStep   현재 진행 step 이름 (nullable, RUNNING 시 set).
 * @param stepIndex     0-based step index (nullable).
 * @param totalSteps    전체 step 수.
 * @param percent       0..100 진행률.
 * @param requestPayload  요청 JSON (audit / replay 용도).
 * @param resultPayload   완료 시 결과 JSON.
 * @param errorMessage    실패 시 메시지.
 * @param requestId       MDC request_id (cross-system trace).
 * @param principal       요청자 principal (audit).
 * @param startedAt       RUNNING 진입 시각.
 * @param endedAt         terminal state 진입 시각.
 * @param createdAt       row 생성 시각 (JPA 가 채움 — domain create 경로는 null).
 * @param updatedAt       row 갱신 시각 (JPA 가 채움).
 */
public record Operation(
        String id,
        OperationType type,
        String resourceType,
        String resourceId,
        OperationState state,
        String currentStep,
        Integer stepIndex,
        Integer totalSteps,
        Integer percent,
        String requestPayload,
        String resultPayload,
        String errorMessage,
        String requestId,
        String principal,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** Terminal state 여부 — 더 이상 progress 갱신 불가. */
    public boolean isTerminal() {
        return state == OperationState.SUCCEEDED || state == OperationState.FAILED || state == OperationState.CANCELLED;
    }

    /** Active (PENDING / RUNNING) — workflow handler 가 progress push 가능. */
    public boolean isActive() {
        return state == OperationState.PENDING || state == OperationState.RUNNING;
    }

    /** Convenience — start 시 호출되는 신규 PENDING domain. */
    public static Operation pending(
            String id,
            OperationType type,
            String resourceType,
            String resourceId,
            String requestPayload,
            int totalSteps) {
        return new Operation(
                id,
                type,
                resourceType,
                resourceId,
                OperationState.PENDING,
                null,
                0,
                totalSteps,
                0,
                requestPayload,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
