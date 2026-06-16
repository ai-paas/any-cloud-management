package com.aipaas.anycloud.common.error.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * ConstraintViolationException 발생 시 Accept 헤더에 따라 응답 형식이 분기되는지 검증.
 *
 * <ul>
 *   <li>일반 JSON 요청 → 기존 ErrorResponse JSON body, Content-Type 미지정 (Spring 이 negotiate)
 *   <li>SSE 요청 (Accept: text/event-stream) → "event: error\ndata: {...}\n\n" body,
 *       Content-Type text/event-stream
 * </ul>
 *
 * <p>이전에는 SSE 엔드포인트의 path-param 검증 실패 시 JSON converter 가 Accept 매칭에 실패하여
 * HttpMediaTypeNotAcceptableException 으로 500 이 났음 — Bruno smoke 중 발견된 regression.
 */
class GlobalExceptionHandlerSseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(objectMapper);

    @Test
    @SuppressWarnings("unchecked")
    void sseAcceptHeader_returnsEventStreamErrorEvent() {
        ConstraintViolationException ex =
                new ConstraintViolationException("test", (Set<ConstraintViolation<?>>) (Set<?>) Set.of());
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Accept")).thenReturn(MediaType.TEXT_EVENT_STREAM_VALUE);

        ResponseEntity<?> response = handler.handleConstraintViolationException(ex, req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(response.getBody()).isInstanceOf(String.class);
        String body = (String) response.getBody();
        assertThat(body).startsWith("event: error\ndata: ");
        assertThat(body).endsWith("\n\n");
        assertThat(body).contains("\"code\":\"INVALID_INPUT_VALUE\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonAcceptHeader_returnsErrorResponseJson() {
        ConstraintViolationException ex =
                new ConstraintViolationException("test", (Set<ConstraintViolation<?>>) (Set<?>) Set.of());
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Accept")).thenReturn(MediaType.APPLICATION_JSON_VALUE);

        ResponseEntity<?> response = handler.handleConstraintViolationException(ex, req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isInstanceOf(ErrorResponse.class);
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body.getCode()).isEqualTo("INVALID_INPUT_VALUE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingAcceptHeader_returnsJsonByDefault() {
        ConstraintViolationException ex =
                new ConstraintViolationException("test", (Set<ConstraintViolation<?>>) (Set<?>) Set.of());
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Accept")).thenReturn(null);

        ResponseEntity<?> response = handler.handleConstraintViolationException(ex, req);

        assertThat(response.getBody()).isInstanceOf(ErrorResponse.class);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mixedAcceptHeader_eventStreamAmongOthers_routesToSse() {
        // 일부 클라이언트는 "text/event-stream,*/*;q=0.5" 형태로 보냄.
        ConstraintViolationException ex =
                new ConstraintViolationException("test", (Set<ConstraintViolation<?>>) (Set<?>) Set.of());
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Accept")).thenReturn("text/event-stream, */*;q=0.5");

        ResponseEntity<?> response = handler.handleConstraintViolationException(ex, req);

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sseEndpointUriEndingInEvents_routesToSse_evenWithJsonAccept() {
        // 클라이언트가 잘못된 Accept (application/json) 으로 SSE endpoint 호출 시 — Spring 의
        // produces=text/event-stream 제약으로 JSON 응답이 NotAcceptable 됨.
        // → URL 가 /events 로 끝나면 강제 SSE 응답.
        ConstraintViolationException ex =
                new ConstraintViolationException("test", (Set<ConstraintViolation<?>>) (Set<?>) Set.of());
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Accept")).thenReturn(MediaType.APPLICATION_JSON_VALUE);
        when(req.getRequestURI()).thenReturn("/v1/operations/bad_id/events");

        ResponseEntity<?> response = handler.handleConstraintViolationException(ex, req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(response.getBody()).isInstanceOf(String.class);
        assertThat((String) response.getBody()).startsWith("event: error\ndata: ");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonSseEndpoint_jsonAccept_returnsJson() {
        // 일반 endpoint 의 validation 실패는 기존 JSON 응답 유지 (회귀 없음).
        ConstraintViolationException ex =
                new ConstraintViolationException("test", (Set<ConstraintViolation<?>>) (Set<?>) Set.of());
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Accept")).thenReturn(MediaType.APPLICATION_JSON_VALUE);
        when(req.getRequestURI()).thenReturn("/v1/clusters/bad name");

        ResponseEntity<?> response = handler.handleConstraintViolationException(ex, req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isInstanceOf(ErrorResponse.class);
    }
}
