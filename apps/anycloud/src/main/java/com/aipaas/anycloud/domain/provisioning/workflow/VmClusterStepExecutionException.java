package com.aipaas.anycloud.domain.provisioning.workflow;

/** Workflow step service 실행 중 발생한 예외를 orchestrator 로 전파하기 위한 도메인 예외. */
public class VmClusterStepExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public VmClusterStepExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public VmClusterStepExecutionException(Throwable cause) {
        super(cause);
    }
}
