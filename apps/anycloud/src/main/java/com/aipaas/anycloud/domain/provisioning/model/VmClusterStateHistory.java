package com.aipaas.anycloud.domain.provisioning.model;

import java.time.LocalDateTime;

/**
 * VmCluster status transition 의 immutable 도메인 표현.
 *
 * <p>JPA 와 분리된 record. 자세한 lifecycle 은
 * {@link com.aipaas.anycloud.domain.provisioning.VmClusterStateHistoryEntity} 의 javadoc 을 참조합니다.
 * 변환은 {@link com.aipaas.anycloud.domain.provisioning.mapper.VmClusterStateHistoryMapper}.
 *
 * @param id           transition row UUID.
 * @param clusterName  대상 cluster 이름.
 * @param vmClusterId  VmCluster row id (nullable — pre-create 단계 transition).
 * @param fromState    이전 status (nullable — initial transition).
 * @param toState      새 status.
 * @param reason       transition 사유 (사용자 / 시스템 메시지).
 * @param principal    transition 호출자.
 * @param requestId    MDC request_id (cross-system trace).
 * @param valid        state machine graph 가 valid transition 으로 판정했는지.
 *                     {@code false} 는 observation mode 에서 그대로 진행된 invalid transition (회귀 추적용).
 * @param createdAt    row 생성 시각.
 */
public record VmClusterStateHistory(
        String id,
        String clusterName,
        String vmClusterId,
        VmClusterStatus fromState,
        VmClusterStatus toState,
        String reason,
        String principal,
        String requestId,
        Boolean valid,
        LocalDateTime createdAt) {

    /** state machine 이 invalid 로 판정한 transition 인지. {@code valid} 가 명시적으로 false 일 때만 true. */
    public boolean isInvalidTransition() {
        return Boolean.FALSE.equals(valid);
    }
}
