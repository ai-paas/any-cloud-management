package com.aipaas.anycloud.error.exception;

import com.aipaas.anycloud.error.enums.ErrorCode;
import lombok.Getter;

@Getter
public class CustomException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String field;
    private final String value;
    private final String reason;
    
    public CustomException(String message, ErrorCode errorCode) {
        super(message);
        this.errorCode = errorCode;
        this.field = null;
        this.value = null;
        this.reason = null;
    }

    public CustomException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.field = null;
        this.value = null;
        this.reason = null;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
    
    public CustomException(ErrorCode errorCode, String field, String value, String reason) {
        super(reason);
        this.errorCode = errorCode;
        this.field = field;
        this.value = value;
        this.reason = reason;
    }
}