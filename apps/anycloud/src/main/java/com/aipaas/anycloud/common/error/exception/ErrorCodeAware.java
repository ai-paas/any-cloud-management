package com.aipaas.anycloud.common.error.exception;

import com.aipaas.anycloud.common.error.enums.ErrorCode;

/** 자신의 HTTP 상태를 아는 예외. 감사 기록과 클라이언트가 받은 상태를 맞추기 위한 것입니다. */
public interface ErrorCodeAware {

    ErrorCode getErrorCode();
}
