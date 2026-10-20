package com.aipaas.anycloud.common.error.handler;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** GlobalExceptionHandler 에서 응답 직렬화 / SSE 포맷팅 / Accept 협상에 쓰는 helper. */
public class ErrorResponseFormatter {

    private final ObjectMapper objectMapper;

    public ErrorResponseFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * SSE 응답 여부 판정. Accept 가 비었거나 와일드카드인 클라이언트 때문에 URI 도 판정 대상.
     *
     * <p>SSE endpoint 에 Accept: application/json 을 명시하면 Spring 의 content negotiation 이
     * SSE 를 거부해 500 응답. 이 지점에서 차단 불가.
     */
    public static boolean acceptsEventStream(HttpServletRequest request) {
        if (request == null) return false;
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return true;
        }
        String uri = request.getRequestURI();
        return uri != null && uri.endsWith("/events");
    }

    /**
     * SSE error event 직렬화. 마지막 빈 줄은 event 종료 마커이므로 제거 금지.
     *
     * <p>직렬화 실패 시 code 와 message 만 직접 escape 해 전송.
     */
    public String toSseErrorEvent(ErrorResponse response) {
        String json;
        try {
            json = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            json = "{\"code\":\"" + response.getCode() + "\",\"message\":\""
                    + response.getMessage().replace("\"", "\\\"") + "\"}";
        }
        return "event: error\ndata: " + json + "\n\n";
    }

    /** Accept 협상 후 SSE 또는 JSON 으로 ResponseEntity 생성. */
    public ResponseEntity<?> respond(HttpServletRequest request, HttpStatus status, ErrorResponse response) {
        if (acceptsEventStream(request)) {
            return ResponseEntity.status(status)
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(toSseErrorEvent(response));
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    /** ErrorCode 기준 HttpStatus 변환 helper: 핸들러에서 status code 산출 반복 제거. */
    public static HttpStatus statusOf(ErrorCode code) {
        return HttpStatus.valueOf(code.getStatus());
    }
}
