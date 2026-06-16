package com.aipaas.anycloud.common.error.exception.provisioning;

import com.aipaas.anycloud.common.error.enums.ErrorCode;

/**
 * 일시적 provisioning 실패 — 외부 시스템 (Pulumi CLI / CSP API / SSH / K8s API) 의 네트워크
 * timeout, 503, transient 자격증명 갱신 지연 등. 호출자는 backoff 재시도가 의미 있음.
 *
 * <p>HTTP 상태는 502 (UPSTREAM_FAILED) 로 매핑되며 응답 메시지에 "재시도 가능" 힌트 포함 권장.
 * 워크플로우 step 에서 본 예외가 던져지면 RabbitMQ retry interceptor 가 재처리하고, maxAttempts
 * 초과 시 DLQ 로 라우팅.
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
