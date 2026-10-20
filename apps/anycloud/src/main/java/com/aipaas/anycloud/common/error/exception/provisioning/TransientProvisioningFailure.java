package com.aipaas.anycloud.common.error.exception.provisioning;

import com.aipaas.anycloud.common.error.enums.ErrorCode;

/**
 * 재시도로 해소 가능한 실패. 네트워크 timeout, 503, 자격증명 갱신 지연 등.
 *
 * <p>워크플로 step 에서 발생 시 RabbitMQ retry interceptor 가 재처리하고 maxAttempts 초과 시
 * DLQ 로 전달.
 */
public class TransientProvisioningFailure extends ProvisioningException {

    public TransientProvisioningFailure(String message) {
        super(message, ErrorCode.UPSTREAM_FAILED);
    }

    public TransientProvisioningFailure(String message, Throwable cause) {
        super(message, ErrorCode.UPSTREAM_FAILED);
        initCause(cause);
    }

    @Override
    public boolean isTransient() {
        return true;
    }
}
