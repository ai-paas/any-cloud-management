package com.aipaas.anycloud.domain.provisioning.model;

/**
 * Workflow 메시지 처리 결과 분류.
 * <p>
 * 운영 시 RabbitMQ at-least-once 재전달 / 단계 비정상 도착 / 실패 원인을 한 컬럼으로 가시화.
 */
public enum WorkflowMessageLogResult {

    /** Orchestrator/step service 가 정상 실행 후 완료. */
    PROCESSED,

    /** 동일 messageId 가 이미 처리됨 (RabbitMQ 재전달). */
    SKIPPED_DUPLICATE,

    /** 현재 cluster 상태 기준으로 이미 지나간 단계 메시지. */
    SKIPPED_STALE,

    /** vmClusterId 가 entity 에 존재하지 않아 가드가 거부. */
    SKIPPED_NOT_FOUND,

    /** 실행 중 예외 발생. error_message 컬럼에 원인 보존. */
    FAILED
}
