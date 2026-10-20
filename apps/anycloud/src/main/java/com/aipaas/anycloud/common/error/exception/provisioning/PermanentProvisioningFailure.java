package com.aipaas.anycloud.common.error.exception.provisioning;

import com.aipaas.anycloud.common.error.enums.ErrorCode;

/**
 * 다시 해도 같은 결과인 실패입니다. 지원하지 않는 provider, 없는 region, IAM 권한 부족, 만료되거나
 * 회수된 자격증명 같은 것입니다.
 *
 * <p>retry interceptor 는 이 예외를 보면 재시도하지 않고 바로 DLQ 로 보냅니다.
 */
public class PermanentProvisioningFailure extends ProvisioningException {

    public PermanentProvisioningFailure(String message, ErrorCode errorCode) {
        super(message, errorCode);
    }

    public PermanentProvisioningFailure(String message, ErrorCode errorCode, Throwable cause) {
        super(message, errorCode);
        initCause(cause);
    }

    @Override
    public boolean isTransient() {
        return false;
    }
}
