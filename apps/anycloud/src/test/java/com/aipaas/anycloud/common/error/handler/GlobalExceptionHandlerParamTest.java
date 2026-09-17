package com.aipaas.anycloud.common.error.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;

/**
 * 필수 query parameter 누락은 호출자 잘못이라 400 이어야 한다.
 *
 * <p>핸들러가 없으면 최종 Exception 으로 떨어져 500 이 나가고, 운영자가 서버 장애로 오인한다.
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
        // 어느 파라미터가 빠졌는지 알려주지 않으면 호출자가 고칠 수 없다.
        var e = new MissingServletRequestParameterException("input", "String");

        var response = handler.handleMissingServletRequestParameterException(e);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).contains("input");
    }

    @Test
    void missingRequestParameter_bodyStatusMatchesHttpStatus() {
        // body 의 status 와 HTTP status 가 어긋나면 클라이언트 분기가 깨진다.
        var e = new MissingServletRequestParameterException("input", "String");

        var response = handler.handleMissingServletRequestParameterException(e);

        assertThat(response.getBody().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }
}
