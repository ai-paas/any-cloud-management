package com.aipaas.anycloud.common.error.enums;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;
import lombok.ToString;

/** 성공 응답 코드와 메시지. */
@Getter
@ToString
@JsonFormat(shape = JsonFormat.Shape.OBJECT)
public enum SuccessCode {

    // Common
    OK(200, "성공."),
    CHECK_ID_OK(200, "사용 가능합니다.");

    private final int status;
    private final String message;

    SuccessCode(final int status, final String message) {
        this.status = status;
        this.message = message;
    }
}
