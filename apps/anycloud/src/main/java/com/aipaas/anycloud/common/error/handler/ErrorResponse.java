package com.aipaas.anycloud.common.error.handler;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.validation.BindingResult;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * <pre>
 * ClassName : ErrorResponse
 * Type : class
 * Description : 에러 메시지 처리와 관련된 함수를 포함하고 있는 클래스입니다.
 * Related : ErrorCode
 * How-to :
 *  1. throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
 *  2. final ErrorResponse response = ErrorResponse.of(ErrorCode.FORBIDDEN);
 * </pre>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Schema(description = "공통 에러 응답 — RFC 9457 Problem Details 호환 (type URI 포함)")
public class ErrorResponse {

    /**
     * RFC 9457 type URI prefix. 클라이언트가 에러 카테고리별 문서/문제 페이지를 파싱할 수 있는
     * stable identifier. 기존 응답 구조는 그대로 유지하고 추가 필드만 노출 — 호환성 보존.
     */
    private static final String PROBLEM_TYPE_PREFIX = "https://anycloud.local/problems/";

    @Schema(
            description = "RFC 9457 problem type URI (안정 식별자)",
            example = "https://anycloud.local/problems/invalid-input-value")
    private String type;

    @Schema(description = "에러 코드", example = "INVALID_INPUT_VALUE")
    private String code;

    @Schema(description = "에러 메시지", example = "잘못된 입력값입니다.")
    private String message;

    @Schema(description = "HTTP 상태 코드", example = "400")
    private int status;

    @Schema(description = "필드 단위 상세 오류 목록")
    private List<FieldError> errors;

    /**
     * 특정 에러에 대한 추가 context (예: UNSUPPORTED_KIND 의 suggestions, RATE_LIMIT 의 retryAfter 등).
     * 핸들러별 정의 — 일반 caller 는 {@code code} 로 분기 후 metadata 의 key 를 해석.
     * null/empty 시 직렬화에서 제외 ({@code @JsonInclude(NON_EMPTY)}).
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(description = "핸들러별 추가 context — code 따라 의미 다름. UNSUPPORTED_KIND 면 'input', 'suggestions' 키 포함.")
    private Map<String, Object> metadata;

    /**
     * 장문 원본 에러 (Pulumi stderr, stack trace 요지 등). {@code message} 는 사람용 한 줄 요약으로
     * 유지하고 전체 원문은 본 필드로 분리 — UI 는 message 만 보여주고 detail 은 펼침/복사용으로 제공.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "원본 에러 전문 (장문). message 는 요약, 전체는 여기.", example = "error: ... full stderr ...")
    private String detail;

    /** 다음 행동 제안 — 사용자가 무엇을 하면 해소되는지 한 줄. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "권장 다음 행동", example = "IAM policy 에 ec2:Describe* 추가 후 재시도하세요.")
    private String hint;

    /**
     * 복구/관련 동작 경로 — 성공 응답의 {@code ApiSuccessResponse.links} 와 동형.
     * 404 면 목록, agent 미연결이면 manifest, 상태 충돌이면 현재 상태 조회 등 frontend 가
     * 바로 이동할 수 있는 URL. null/empty 면 직렬화 제외.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(description = "복구/관련 동작 URL (HATEOAS-lite). 예: clusterList, agentManifest, status.")
    private Map<String, String> links;

    private ErrorResponse(final ErrorCode code, final List<FieldError> errors) {
        this.type = typeUriOf(code);
        this.code = code.name();
        this.status = code.getStatus();
        this.message = code.getMessage();
        this.errors = errors;
    }

    private ErrorResponse(final ErrorCode code) {
        this.type = typeUriOf(code);
        this.code = code.name();
        this.status = code.getStatus();
        this.message = code.getMessage();
        this.errors = new ArrayList<>();
    }

    private ErrorResponse(final ErrorCode code, final String customMessage) {
        this.type = typeUriOf(code);
        this.code = code.name();
        this.status = code.getStatus();
        this.message = customMessage;
        this.errors = new ArrayList<>();
    }

    private ErrorResponse(final ErrorCode code, final String customMessage, final List<FieldError> errors) {
        this.type = typeUriOf(code);
        this.code = code.name();
        this.status = code.getStatus();
        this.message = customMessage;
        this.errors = errors;
    }

    private static String typeUriOf(ErrorCode code) {
        // enum NAME → kebab-case slug. INVALID_INPUT_VALUE → invalid-input-value.
        return PROBLEM_TYPE_PREFIX
                + code.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    public static ErrorResponse of(final ErrorCode code, final BindingResult bindingResult) {
        return new ErrorResponse(code, FieldError.of(bindingResult));
    }

    public static ErrorResponse of(final ErrorCode code) {
        return new ErrorResponse(code);
    }

    public static ErrorResponse of(final ErrorCode code, final String customMessage) {
        return new ErrorResponse(code, customMessage);
    }

    public static ErrorResponse of(final ErrorCode code, final List<FieldError> errors) {
        return new ErrorResponse(code, errors);
    }

    /** customMessage + errors 모두 override — NO_BODY 등 cause hint 가 필요한 케이스 용. */
    public static ErrorResponse of(final ErrorCode code, final String customMessage, final List<FieldError> errors) {
        return new ErrorResponse(code, customMessage, errors);
    }

    /**
     * customMessage + metadata override — UNSUPPORTED_KIND 의 suggestions 등 추가 context 노출.
     * metadata 가 null/empty 면 직렬화에서 제외.
     */
    public static ErrorResponse of(
            final ErrorCode code, final String customMessage, final Map<String, Object> metadata) {
        ErrorResponse r = new ErrorResponse(code, customMessage);
        r.metadata = metadata;
        return r;
    }

    /** message 가 한 줄 요약 한계를 넘으면 message=요약 + detail=원문 으로 자동 분할. */
    private static final int MESSAGE_SUMMARY_LIMIT = 200;

    /**
     * 장문 메시지를 요약/원문으로 분할해 생성. 원문이 {@value #MESSAGE_SUMMARY_LIMIT} 자 이하의
     * 단일 라인이면 message 만 채우고 detail 은 생략.
     */
    public static ErrorResponse ofSummarized(final ErrorCode code, final String fullMessage) {
        if (fullMessage == null || fullMessage.isBlank()) {
            return new ErrorResponse(code);
        }
        String firstLine = fullMessage.lines().findFirst().orElse(fullMessage).strip();
        boolean multiline = fullMessage.lines().count() > 1;
        if (!multiline && firstLine.length() <= MESSAGE_SUMMARY_LIMIT) {
            return new ErrorResponse(code, firstLine);
        }
        String summary = firstLine.length() <= MESSAGE_SUMMARY_LIMIT
                ? firstLine
                : firstLine.substring(0, MESSAGE_SUMMARY_LIMIT) + "…";
        ErrorResponse r = new ErrorResponse(code, summary + " (전체 원문: detail 필드)");
        r.detail = fullMessage;
        return r;
    }

    public ErrorResponse withDetail(final String detail) {
        this.detail = detail;
        return this;
    }

    public ErrorResponse withHint(final String hint) {
        this.hint = hint;
        return this;
    }

    public ErrorResponse withLinks(final Map<String, String> links) {
        this.links = links;
        return this;
    }

    public static ErrorResponse of(MethodArgumentTypeMismatchException e) {
        final String value = e.getValue() == null ? "" : e.getValue().toString();
        final List<ErrorResponse.FieldError> errors = ErrorResponse.FieldError.of(e.getName(), value, e.getErrorCode());
        return new ErrorResponse(ErrorCode.INVALID_TYPE_VALUE, errors);
    }

    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @Schema(description = "필드 단위 오류 정보")
    public static class FieldError {
        @Schema(description = "오류가 발생한 필드", example = "clusterName")
        private String field;

        @Schema(description = "입력된 값", example = "")
        private String value;

        @Schema(description = "오류 사유", example = "must not be blank")
        private String reason;

        private FieldError(final String field, final String value, final String reason) {
            this.field = field;
            this.value = value;
            this.reason = reason;
        }

        public static List<FieldError> of(final String field, final String value, final String reason) {
            List<FieldError> fieldErrors = new ArrayList<>();
            fieldErrors.add(new FieldError(field, value, reason));
            return fieldErrors;
        }

        private static List<FieldError> of(final BindingResult bindingResult) {
            final List<org.springframework.validation.FieldError> fieldErrors = bindingResult.getFieldErrors();
            return fieldErrors.stream()
                    .map(error -> new FieldError(
                            error.getField(),
                            error.getRejectedValue() == null
                                    ? ""
                                    : error.getRejectedValue().toString(),
                            error.getDefaultMessage()))
                    .collect(Collectors.toList());
        }
    }
}
