package com.aipaas.anycloud.domain.provisioning.model;

import com.aipaas.anycloud.domain.credential.model.CspCredentialSourceType;
import com.aipaas.anycloud.domain.provisioning.workflow.VmClusterWorkflowStep;
import java.time.LocalDateTime;

/**
 * VM-provisioned cluster 의 immutable 도메인 표현.
 *
 * <p>JPA / persistence 와 분리된 순수 자바 record. workflow 상태 기계 (PROVISION → BOOTSTRAP →
 * VERIFY → READY) 와 step 별 timestamp 를 한 record 로 캡처.
 *
 * <p>도메인 ↔ JPA 변환은 service 계층에서 처리 (현재 별도 mapper 클래스 없음 — H-D 시점에 unused
 * VmClusterMapper 삭제됨. 사용처가 신설되면 MapStruct {@code @Mapper(componentModel = "spring")}
 * interface 로 신규 작성).
 */
public record VmCluster(
        String id,
        String clusterName,
        String description,
        String clusterProvider,
        VmClusterStatus provisioningStatus,
        String stackName,
        String region,
        String environment,
        String activeRequestKey,
        String credentialId,
        String credentialName,
        CspCredentialSourceType credentialSourceType,
        String requestConfig,
        String rawOutputs,
        String lastError,
        String bootstrapLog,
        VmClusterWorkflowStep currentWorkflowStep,
        VmClusterWorkflowStep lastSuccessfulStep,
        VmClusterWorkflowStep lastFailedStep,
        Integer workflowRetryCount,
        String lastProcessedWorkflowMessageId,
        LocalDateTime requestedAt,
        LocalDateTime provisioningStartedAt,
        LocalDateTime bootstrappingStartedAt,
        LocalDateTime verifyingStartedAt,
        LocalDateTime readyAt,
        LocalDateTime failedAt,
        LocalDateTime deletingStartedAt,
        LocalDateTime deletedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** Active workflow 진행 중 — terminal state (READY, FAILED, DELETED) 가 아님. */
    public boolean isInProgress() {
        return provisioningStatus != null
                && provisioningStatus != VmClusterStatus.READY
                && provisioningStatus != VmClusterStatus.FAILED
                && provisioningStatus != VmClusterStatus.DELETED;
    }

    /** READY 상태 — 사용 가능. */
    public boolean isReady() {
        return provisioningStatus == VmClusterStatus.READY;
    }

    /** FAILED 상태 — error 진단 필요. */
    public boolean isFailed() {
        return provisioningStatus == VmClusterStatus.FAILED;
    }
}
