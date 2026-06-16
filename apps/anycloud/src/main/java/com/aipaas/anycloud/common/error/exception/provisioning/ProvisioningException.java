package com.aipaas.anycloud.common.error.exception.provisioning;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;

/**
 * Provisioning 도메인 예외의 베이스. {@link CustomException} 상속이라 기존
 * {@code GlobalExceptionHandler#handleBusinessException} 가 그대로 캐치하지만, 의미상
 * 분류를 위해 별도 hierarchy 를 둔다.
 *
 * <p>하위 클래스는 transient (재시도 가능) / permanent (영구 실패) 로 명확히 분리.
 * 외부 시스템 (Pulumi / CSP API / SSH) 호출 실패는 transient, 입력/상태 충돌은 permanent.
 *
 * <p>{@link #isTransient()} 가 true 면 retry interceptor 가 재시도를 시도해야 함을
 * 호출자가 판단할 수 있다.
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
