package com.aipaas.anycloud.common.error.exception.provisioning;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;

/**
 * provisioning 예외의 베이스입니다.
 *
 * <p>하위 클래스는 transient 와 permanent 로 나뉩니다. Pulumi, CSP API, SSH 같은 외부 호출 실패는
 * transient 고 입력이나 상태 충돌은 permanent 입니다. {@link #isTransient()} 로 retry interceptor 가
 * 재시도 여부를 정하므로 둘을 섞으면 영구 실패를 계속 재시도하게 됩니다.
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
