package com.aipaas.anycloud.common.error.handler;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * GlobalExceptionHandler 에서 응답 직렬화 / SSE 포맷팅 / Accept 협상에 쓰는 helper.
 *
 * <p>핸들러 본문은 "어떤 ErrorCode 로 매핑할지" 결정에만 집중하게 하고, 직렬화 / content-type
 * 분기는 본 helper 가 담당. 동일 패턴 (JSON vs SSE 응답) 이 controller advisor 여러 곳에서
 * 반복되지 않도록 추출.
 *
 * <p>본 클래스는 stateless — Spring bean 으로 등록하지 않고 정적 사용 또는 인스턴스 주입 모두
 * 가능. ObjectMapper 만 필요하므로 instance method 로 둠.
 */
public class ErrorResponseFormatter {

    private final ObjectMapper objectMapper;

    public ErrorResponseFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 요청이 SSE 응답을 받을 수 있는지 판단:
     * <ol>
     *   <li>Accept 헤더에 text/event-stream 포함, 또는
     *   <li>request URI 가 SSE 전용 endpoint 패턴 (.../events) — Accept 누락 / wildcard 케이스 cover.
     * </ol>
     *
     * <p><b>한계</b>: 클라이언트가 명시적으로 Accept: application/json 을 SSE endpoint 에 보낸
     * 경우 Spring content negotiation 이 SSE 응답을 거부 → 500 fallback. 정상 SSE 클라이언트는
     * 항상 Accept: text/event-stream 송신.
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
     * ErrorResponse → SSE 'error' event 직렬화.
     *
     * <pre>
     *   event: error
     *   data: {"code":"INVALID_INPUT_VALUE","message":"...","status":400, ...}
     *
     * </pre>
     *
     * 마지막 빈 줄은 SSE 프로토콜 상 event 종료 마커. 직렬화 실패는 거의 불가능하나 fallback 으로
     * code+message 만 직접 escape.
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

    /** ErrorCode 기준 HttpStatus 변환 helper — 핸들러에서 status code 산출 반복 제거. */
    public static HttpStatus statusOf(ErrorCode code) {
        return HttpStatus.valueOf(code.getStatus());
    }
}
