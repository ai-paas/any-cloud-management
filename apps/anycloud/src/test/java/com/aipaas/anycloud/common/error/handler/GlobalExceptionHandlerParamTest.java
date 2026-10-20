package com.aipaas.anycloud.common.error.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;

/**
 * 필수 query parameter 누락은 호출자 오류이므로 400 응답.
 *
 * <p>핸들러 부재 시 최종 Exception 으로 전파돼 500 응답. 운영자가 서버 장애로 오인.
 */
class GlobalExceptionHandlerParamTest extends AbstractUnitTest {

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    void missingRequestParameter_isBadRequestNotServerError() {
        var e = new MissingServletRequestParameterException("input", "String");

        var response = handler.handleMissingServletRequestParameterException(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void missingRequestParameter_namesTheParameter() {
        // 누락 파라미터를 안내하지 않으면 호출자가 조치 불가.
        var e = new MissingServletRequestParameterException("input", "String");

        var response = handler.handleMissingServletRequestParameterException(e);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).contains("input");
    }

    @Test
    void missingRequestParameter_bodyStatusMatchesHttpStatus() {
        // body 의 status 와 HTTP status 불일치 시 클라이언트 분기 실패.
        var e = new MissingServletRequestParameterException("input", "String");

        var response = handler.handleMissingServletRequestParameterException(e);

        assertThat(response.getBody().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }
}
