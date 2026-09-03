package com.aipaas.anycloud.common.error.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class ErrorResponseFormatterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ErrorResponseFormatter formatter = new ErrorResponseFormatter(objectMapper);

    @Test
    void statusOfMirrorsErrorCodeStatus() {
        assertThat(ErrorResponseFormatter.statusOf(ErrorCode.STATE_CONFLICT)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorResponseFormatter.statusOf(ErrorCode.UPSTREAM_FAILED)).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void acceptsEventStreamRecognisesAcceptHeader() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Accept")).thenReturn("text/event-stream, application/json");
        assertThat(ErrorResponseFormatter.acceptsEventStream(req)).isTrue();
    }

    @Test
    void acceptsEventStreamRecognisesEventsSuffixWithoutHeader() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Accept")).thenReturn("*/*");
        when(req.getRequestURI()).thenReturn("/v1/operations/op-1/events");
        assertThat(ErrorResponseFormatter.acceptsEventStream(req)).isTrue();
    }

    @Test
    void acceptsEventStreamFalseForPlainJsonRequest() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Accept")).thenReturn(MediaType.APPLICATION_JSON_VALUE);
        when(req.getRequestURI()).thenReturn("/v1/clusters");
        assertThat(ErrorResponseFormatter.acceptsEventStream(req)).isFalse();
    }

    @Test
    void acceptsEventStreamFalseForNullRequest() {
        assertThat(ErrorResponseFormatter.acceptsEventStream(null)).isFalse();
    }

    @Test
    void sseEventWrapsResponseAsErrorEvent() {
        ErrorResponse response = ErrorResponse.of(ErrorCode.STATE_CONFLICT, "only READY clusters scale");
        String sse = formatter.toSseErrorEvent(response);
        assertThat(sse).startsWith("event: error\ndata: ");
        assertThat(sse).endsWith("\n\n");
        assertThat(sse).contains("STATE_CONFLICT");
    }

    @Test
    void respondReturnsJsonForNonSseRequest() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Accept")).thenReturn(MediaType.APPLICATION_JSON_VALUE);
        when(req.getRequestURI()).thenReturn("/v1/clusters");
        ErrorResponse response = ErrorResponse.of(ErrorCode.STATE_CONFLICT);

        var entity = formatter.respond(req, HttpStatus.CONFLICT, response);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(entity.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(entity.getBody()).isInstanceOf(ErrorResponse.class);
    }

    @Test
    void respondReturnsSseForEventStreamRequest() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Accept")).thenReturn(MediaType.TEXT_EVENT_STREAM_VALUE);
        ErrorResponse response = ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE);

        var entity = formatter.respond(req, HttpStatus.BAD_REQUEST, response);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(entity.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(entity.getBody()).isInstanceOf(String.class);
        assertThat((String) entity.getBody()).contains("event: error");
    }
}
