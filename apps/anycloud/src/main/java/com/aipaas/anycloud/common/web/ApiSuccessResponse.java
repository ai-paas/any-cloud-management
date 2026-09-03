package com.aipaas.anycloud.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * 모든 성공 응답의 envelope. record 로 immutable.
 * <p>
 * 컨트롤러는 보통 {@link #of(int, String, Object)} 만 사용하면 충분하다. meta(requestId/timestamp/
 * processingTimeMs) 는 {@code ResponseEnvelopeAdvice} 가 응답 직전 자동으로 채워준다.
 * links 가 필요하면 {@link #withLinks(Map)} 로 추가.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "공통 성공 응답 envelope")
public record ApiSuccessResponse<T>(
        @Schema(description = "요청 성공 여부", example = "true") boolean success,
        @Schema(description = "HTTP 상태 코드", example = "200") int status,
        @Schema(description = "응답 메시지", example = "Clusters loaded") String message,
        @Schema(description = "실제 응답 데이터") T data,
        @Schema(description = "요청 추적 / 페이지네이션 메타") ResponseMeta meta,
        @Schema(description = "HATEOAS-lite 다음 동작 url 들") Map<String, String> links) {

    public static <T> ApiSuccessResponse<T> of(int status, String message, T data) {
        return new ApiSuccessResponse<>(true, status, message, data, null, null);
    }

    public ApiSuccessResponse<T> withMeta(ResponseMeta meta) {
        return new ApiSuccessResponse<>(success, status, message, data, meta, links);
    }

    public ApiSuccessResponse<T> withLinks(Map<String, String> links) {
        return new ApiSuccessResponse<>(success, status, message, data, meta, links);
    }

    public ApiSuccessResponse<T> withPagedMeta(int pageSize, String nextPageToken, Long totalEstimate) {
        ResponseMeta base = meta == null ? ResponseMeta.of(null, null, null) : meta;
        return withMeta(base.withPagination(new ResponseMeta.Pagination(pageSize, nextPageToken, totalEstimate)));
    }
}
