package com.aipaas.anycloud.common.error.exception.provisioning;

import com.aipaas.anycloud.common.error.enums.ErrorCode;

/**
 * 재시도해도 결과가 동일한 실패. 미지원 provider, 존재하지 않는 region, IAM 권한 부족, 만료
 * 또는 회수된 자격증명 등.
 *
 * <p>retry interceptor 는 이 예외에 대해 재시도 없이 DLQ 로 전달.
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
