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
     * SSE 로 응답할지 판단합니다. URI 로도 보는 이유는 Accept 가 비었거나 와일드카드인 클라이언트
     * 때문입니다.
     *
     * <p>SSE endpoint 에 Accept: application/json 을 명시해 보내면 Spring 의 content negotiation 이
     * SSE 를 거부해 500 으로 떨어집니다. 여기서 막을 수 없습니다.
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
     * SSE error event 로 직렬화합니다. 끝의 빈 줄은 event 종료 마커라 빼면 안 됩니다.
     *
     * <p>직렬화가 실패하면 code 와 message 만 직접 escape 해 내보냅니다.
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
