package com.aipaas.anycloud.common.error.exception;

import com.aipaas.anycloud.common.error.enums.ErrorCode;

/** 자체 HTTP 상태를 보유한 예외. 감사 기록과 클라이언트 수신 상태를 일치시키기 위함. */
public interface ErrorCodeAware {

    ErrorCode getErrorCode();
}
