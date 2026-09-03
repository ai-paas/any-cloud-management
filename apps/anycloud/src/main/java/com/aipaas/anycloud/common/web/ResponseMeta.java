package com.aipaas.anycloud.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 모든 응답 공통 메타. 클라이언트가 별도 헤더를 보지 않아도 body 만으로 디버깅에 필요한 정보를
 * 모두 얻을 수 있도록 제공.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "응답 메타 — 요청 추적/시간/페이지네이션 정보")
public record ResponseMeta(
        @Schema(description = "요청 추적 id (헤더 X-Request-Id 와 동일)", example = "abc12345") String requestId,
        @Schema(description = "응답 발생 시각 (ISO 8601)", example = "2026-05-11T03:45:21.123Z") String timestamp,
        @Schema(description = "서버 처리 시간 (ms)", example = "42") Long processingTimeMs,
        @Schema(description = "페이지네이션 — list 응답일 때만") Pagination pagination) {

    public static ResponseMeta of(String requestId, String timestamp, Long processingTimeMs) {
        return new ResponseMeta(requestId, timestamp, processingTimeMs, null);
    }

    public ResponseMeta withPagination(Pagination pagination) {
        return new ResponseMeta(requestId, timestamp, processingTimeMs, pagination);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "페이지네이션 정보")
    public record Pagination(
            @Schema(description = "요청한 페이지 크기", example = "100") int pageSize,
            @Schema(description = "다음 페이지 호출용 opaque cursor. null/빈 문자열이면 마지막 페이지.") String nextPageToken,
            @Schema(description = "총 항목 수 (estimate, 없을 수 있음)") Long totalEstimate) {}
}
