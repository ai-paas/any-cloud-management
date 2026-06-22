package com.aipaas.anycloud.common.error.exception.provisioning;

/**
 * Pulumi CLI 실행 (up / preview / destroy / refresh) 자체가 실패한 경우. starter 의
 * {@code ProvisioningExecutionException} 과 의미적으로 동일하지만, anycloud host 측에서 추가
 * 컨텍스트 (cluster id / operation id) 를 함께 전달하기 위한 wrapper.
 *
 * <p>외부 시스템 호출이라 기본 {@link TransientProvisioningFailure} 로 분류 — 호출자는
 * "Pulumi 출력의 root cause 가 영구 (잘못된 region 등) 면 PermanentProvisioningFailure 로
 * 재분류" 하는 정책을 적용가능.
 */
public class PulumiExecutionException extends TransientProvisioningFailure {

    public PulumiExecutionException(String message) {
        super(message);
    }

    public PulumiExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
