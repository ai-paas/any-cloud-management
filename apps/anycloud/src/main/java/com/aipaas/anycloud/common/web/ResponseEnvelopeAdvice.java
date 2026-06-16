package com.aipaas.anycloud.common.web;

import com.aipaas.anycloud.common.logging.LoggingMdc;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 모든 {@link ApiSuccessResponse} 응답에 자동으로 meta(requestId/timestamp/processingTimeMs) 첨부.
 * 컨트롤러는 비즈니스 데이터만 채우고 envelope 메타는 신경 쓸 필요 없다.
 * <p>
 * 처리 시간은 {@link RequestMdcFilter} 와 다른 인터셉터가 설정한 {@code REQUEST_START_NS}
 * attribute 가 있으면 거기서 측정, 없으면 null.
 */
@RestControllerAdvice
public class ResponseEnvelopeAdvice implements ResponseBodyAdvice<Object> {

    public static final String REQUEST_START_NS_ATTR = "anycloud.request.startNs";

    @Override
    public boolean supports(MethodParameter returnType, Class converterType) {
        return ApiSuccessResponse.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (!(body instanceof ApiSuccessResponse<?> envelope)) {
            return body;
        }
        // 이미 meta 가 있으면(예: 컨트롤러가 pagination 첨부) requestId/timestamp/processingTime 만 보강.
        ResponseMeta enriched = enrichMeta(envelope.meta(), request);
        return envelope.withMeta(enriched);
    }

    private ResponseMeta enrichMeta(ResponseMeta existing, ServerHttpRequest request) {
        String requestId = LoggingMdc.snapshot().get(LoggingMdc.REQUEST_ID);
        String timestamp = Instant.now().toString();
        Long processingTimeMs = computeProcessingMs(request);

        if (existing == null) {
            return ResponseMeta.of(requestId, timestamp, processingTimeMs);
        }
        // 컨트롤러가 직접 채운 값을 우선하되 비어 있으면 채워준다.
        return new ResponseMeta(
                existing.requestId() != null ? existing.requestId() : requestId,
                existing.timestamp() != null ? existing.timestamp() : timestamp,
                existing.processingTimeMs() != null ? existing.processingTimeMs() : processingTimeMs,
                existing.pagination());
    }

    private Long computeProcessingMs(ServerHttpRequest request) {
        if (!(request instanceof ServletServerHttpRequest servletReq)) {
            return null;
        }
        HttpServletRequest http = servletReq.getServletRequest();
        Object startObj = http.getAttribute(REQUEST_START_NS_ATTR);
        if (!(startObj instanceof Long startNs)) {
            return null;
        }
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
