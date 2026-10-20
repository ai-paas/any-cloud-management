package com.aipaas.anycloud.common.error.exception.provisioning;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;

/**
 * provisioning 예외의 기반 클래스.
 *
 * <p>하위 클래스는 transient 와 permanent 로 구분. Pulumi, CSP API, SSH 등 외부 호출 실패는
 * transient, 입력 오류나 상태 충돌은 permanent. retry interceptor 가 {@link #isTransient()} 로
 * 재시도 여부를 판정하므로 혼용 시 영구 실패를 반복 재시도.
 */
public abstract class ProvisioningException extends CustomException {

    protected ProvisioningException(String message, ErrorCode errorCode) {
        super(message, errorCode);
    }

    protected ProvisioningException(ErrorCode errorCode) {
        super(errorCode);
    }

    /** 재시도가 의미 있는 transient 실패 여부. retry 정책 결정에 사용. */
    public abstract boolean isTransient();
}
