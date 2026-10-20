package com.aipaas.anycloud.common.error.exception.provisioning;

import com.aipaas.anycloud.common.error.enums.ErrorCode;

/**
 * 다시 해보면 될 수 있는 실패입니다. 네트워크 timeout, 503, 자격증명 갱신 지연 같은 것입니다.
 *
 * <p>워크플로 step 에서 던지면 RabbitMQ retry interceptor 가 재처리하고 maxAttempts 를 넘기면 DLQ 로
 * 보냅니다.
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
