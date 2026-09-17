package com.aipaas.anycloud.common.error.exception;

import com.aipaas.anycloud.common.error.enums.ErrorCode;

/** 자신의 HTTP 상태를 아는 예외. 감사 기록이 클라이언트가 받은 상태와 어긋나지 않게 한다. */
public interface ErrorCodeAware {

    ErrorCode getErrorCode();
}
