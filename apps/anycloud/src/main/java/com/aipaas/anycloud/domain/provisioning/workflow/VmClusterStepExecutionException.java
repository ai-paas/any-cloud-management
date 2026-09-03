package com.aipaas.anycloud.domain.provisioning.workflow;

/**
 * Workflow step service 실행 중 발생한 예외를 orchestrator 로 전파하기 위한 도메인 예외.
 * <p>
 * Step service 는 entity 상태 저장(workflowSupportService.fail/failWithDiagnostics) 까지
 * 마친 뒤 이 예외를 던진다. Orchestrator 가 catch 해서 workflow_message_log 에 FAILED 로 기록하고
 * 메시지 처리 자체는 정상 종료시킨다(다음 메시지 차단을 위해 RabbitMQ 로 nack 하지 않음).
 */
public class VmClusterStepExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public VmClusterStepExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public VmClusterStepExecutionException(Throwable cause) {
        super(cause);
    }
}
