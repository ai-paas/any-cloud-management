package com.aipaas.anycloud.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.aipaas.anycloud.error.enums.SuccessCode;
import lombok.*;

/**
 * <pre>
 * ClassName : ResultResponse
 * Type : class
 * Description : Success 메시지 처리와 관련된 함수를 포함하고 있는 클래스입니다.
 * Related : ErrorCode
 * How-to :
 *  1. final ResultResponse response = ResultResponse.of(SuccessCode.OK);
 * </pre>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@JsonInclude(JsonInclude.Include.NON_NULL) // null 일 때만 필드를 무시
public class ResultResponse {
    private String message;
    private Object data;
    private int status;

    private ResultResponse(final SuccessCode code) {
        this.status = code.getStatus();
        this.message = code.getMessage();
    }

    private ResultResponse(final SuccessCode code, final Object data) {
        this.status = code.getStatus();
        this.data = data;
    }

    public static ResultResponse of(final SuccessCode code) {
        return new ResultResponse(code);
    }

    public static ResultResponse of(final SuccessCode code, final Object data) {
        return new ResultResponse(code, data);
    }

}
