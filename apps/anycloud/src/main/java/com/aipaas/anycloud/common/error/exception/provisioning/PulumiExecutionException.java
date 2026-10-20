package com.aipaas.anycloud.common.error.exception.provisioning;

/**
 * Pulumi CLI 실행 자체가 실패했습니다. starter 의 {@code ProvisioningExecutionException} 에
 * cluster id 와 operation id 를 얹어 전달합니다.
 *
 * <p>외부 호출이라 기본은 {@link TransientProvisioningFailure} 입니다. Pulumi 출력의 원인이
 * 잘못된 region 처럼 영구적이면 호출자가 {@code PermanentProvisioningFailure} 로 다시 분류하세요.
 * 그대로 두면 고칠 수 없는 실패를 계속 재시도합니다.
 */
public class PulumiExecutionException extends TransientProvisioningFailure {

    public PulumiExecutionException(String message) {
        super(message);
    }

    public PulumiExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
