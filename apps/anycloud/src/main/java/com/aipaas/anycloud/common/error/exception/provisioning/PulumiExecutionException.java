package com.aipaas.anycloud.common.error.exception.provisioning;

/**
 * Pulumi CLI 실행 실패. starter 의 {@code ProvisioningExecutionException} 에 cluster id 와
 * operation id 를 추가해 전달.
 *
 * <p>외부 호출이므로 기본은 {@link TransientProvisioningFailure}. Pulumi 출력의 원인이 잘못된
 * region 처럼 영구적이면 호출자가 {@code PermanentProvisioningFailure} 로 재분류. 미분류 시
 * 복구 불가 실패를 반복 재시도.
 */
public class PulumiExecutionException extends TransientProvisioningFailure {

    public PulumiExecutionException(String message) {
        super(message);
    }

    public PulumiExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
