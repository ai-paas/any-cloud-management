package com.aipaas.anycloud.common.error.exception.provisioning;

import com.aipaas.anycloud.common.error.enums.ErrorCode;

/**
 * 영구적 provisioning 실패 — 잘못된 입력 (provider 미지원 / region 없음 / spec 부적합),
 * permission 거부 (CSP IAM 부족), 자격증명 root cause 가 영구 (만료 / revoke) 등. 재시도해도
 * 동일 결과.
 *
 * <p>HTTP 상태는 ErrorCode 따라 4xx (입력) 또는 502 (외부 영구 거부). retry 정책은 본 예외를
 * 보면 즉시 DLQ 처리해야 함.
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
