package com.aipaas.anycloud.common.error.handler;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.ClusterNotFoundException;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.common.error.exception.EntityNotFoundException;
import com.aipaas.anycloud.common.error.exception.HelmChartNotFoundException;
import com.aipaas.anycloud.common.error.exception.HelmDeploymentException;
import com.aipaas.anycloud.common.error.exception.HelmRepositoryNotFoundException;
import com.aipaas.anycloud.common.error.exception.provisioning.ProvisioningException;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.backup.core.BackupException;
import io.aipaas.cluster.agent.observability.core.ObservabilityException;
import io.aipaas.cluster.agent.runtime.UnsupportedKindException;
import io.aipaas.cluster.provisioning.api.exception.ProvisioningExecutionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.nio.file.AccessDeniedException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 글로벌 예외 → ErrorResponse 매핑.
 *
 * <p>핸들러는 의도적으로 카테고리 별 섹션으로 정리. 새 핸들러 추가 시 적절한 카테고리에 둘 것.
 *
 * <ol>
 *   <li><b>VALIDATION</b> — 입력 검증 실패. 400. {@code HttpMessageNotReadableException},
 *       {@code MethodArgumentNotValidException}, {@code ConstraintViolationException},
 *       {@code MethodArgumentTypeMismatchException}, {@code IllegalArgumentException}.</li>
 *   <li><b>NOT FOUND / METHOD</b> — 리소스 / 메서드 매칭 실패. 404 / 405.
 *       {@code NoResourceFoundException}, {@code HttpRequestMethodNotSupportedException},
 *       JPA / 도메인 NotFound 예외 (ClusterNotFound, HelmRepoNotFound, …).</li>
 *   <li><b>BUSINESS / STATE</b> — 도메인 로직 + 상태 충돌. {@code CustomException} (전반적
 *       호환 catch-all), {@code IllegalStateException}, {@code ProvisioningException} (transient
 *       / permanent / state-conflict / pulumi).</li>
 *   <li><b>EXTERNAL</b> — Pulumi / starter / agent route 실패. 502 / 503 / 504.
 *       {@code ProvisioningExecutionException} (starter), {@code ObservabilityException},
 *       {@code BackupException}, {@code UnsupportedKindException}.</li>
 *   <li><b>SECURITY / DATA</b> — auth / duplicate. 403 / 409.
 *       {@code AccessDeniedException}, {@code DuplicateKeyException}.</li>
 *   <li><b>FALLBACK</b> — 그 외 모든 {@code Exception} 은 500.</li>
 * </ol>
 *
 * <p>직렬화 / SSE 응답 / status 변환은 {@link ErrorResponseFormatter} 로 분리됨. 본 클래스는
 * "어떤 ErrorCode 로 매핑할지" 결정에만 집중.
 *
 * <p>신규 provisioning 코드는 {@code TransientProvisioningFailure} / {@code PermanentProvisioningFailure}
 * 를 throw 한다 — retry classifier 가 {@code isTransient()} 로 RabbitMQ DLQ 라우팅 결정.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper;
    private final ErrorResponseFormatter formatter;

    public GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.formatter = new ErrorResponseFormatter(objectMapper);
    }

    /**
     * Body 가 비어있거나 JSON parse 실패 시 발생. 두 가지 sub-case 로 분기 :
     *
     * <ol>
     *   <li>"Required request body is missing" — body 가 0 byte 도착 (클라이언트가 안 보냄).
     *       Content-Length 헤더 0 / Bruno 의 변수 substitution 실패 등.</li>
     *   <li>그 외 — JSON syntax error, escape 오류, 필드 type mismatch 등. cause class 가
     *       Jackson 의 JsonParseException / JsonMappingException 등으로 구체.</li>
     * </ol>
     *
     * cause 의 raw message 는 사용자 input 일부 포함 가능성이 있어 log 만, 클라이언트 응답에는
     * cause class 명만 (보안 + 진단 균형).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    protected ResponseEntity<ErrorResponse> httpMessageNotReadableExceptionHandler(
            HttpMessageNotReadableException e, jakarta.servlet.http.HttpServletRequest request) {
        Throwable cause = e.getMostSpecificCause();
        String causeClass = cause == null ? "<none>" : cause.getClass().getSimpleName();
        String causeMsg = cause == null ? "<none>" : cause.getMessage();
        // 실제 도착한 request 의 헤더 정보를 함께 log — 클라이언트 (Bruno 등) 가 보낸 게 진짜
        // empty 인지, Content-Type 이 어떤지, charset 이 뭔지 확인 가능.
        log.error(
                "HttpMessageNotReadable: {} | contentLength={} contentType={} method={} uri={} | cause class={} cause msg={}",
                e.getMessage(),
                request.getContentLength(),
                request.getContentType(),
                request.getMethod(),
                request.getRequestURI(),
                causeClass,
                causeMsg);

        // sub-case 분기 — body missing 인지 JSON parse 실패인지.
        boolean bodyMissing = e.getMessage() != null && e.getMessage().contains("Required request body is missing");

        final String hint;
        final String reason;
        if (bodyMissing) {
            hint = "Request body 가 비어있습니다 (Content-Length=0 또는 본문 미전송). "
                    + "확인 사항: "
                    + "(1) HTTP client 의 body 가 실제로 채워졌는지 (Bruno 의 'body:json { ... }' block 또는 multipart form), "
                    + "(2) 클라이언트의 body 변수 substitution 이 성공했는지 ({{unresolved}} 가 있으면 body 가 통째 skip 될 수 있음), "
                    + "(3) Bruno 사용 시 vars 의 빈 값 (예: 'version:') 이 parser 를 깨지 않는지 — 사용 안 하는 변수는 vars 에서 제거 권장, "
                    + "(4) Content-Type 헤더가 application/json 으로 매칭되는지. "
                    + "Path variable (예: cluster_name) 의 정합성 검증은 별도 에러 (INVALID_INPUT_VALUE / ENTITY_NOT_FOUND).";
            reason = "RequestBodyMissing";
        } else {
            hint = "Request body 를 JSON 으로 parse 하지 못했습니다. 자주 보는 원인: "
                    + "(1) JSON syntax error (괄호·콤마·따옴표), "
                    + "(2) 따옴표 또는 줄바꿈 escape (\\\\n, \\\\\") 오류, "
                    + "(3) JSON 안에 raw control character, "
                    + "(4) 필드 타입 불일치. "
                    + "정확한 원인은 errors[0].reason 의 cause class + server log 의 cause msg 참고.";
            reason = causeClass;
        }
        final java.util.List<ErrorResponse.FieldError> errors =
                ErrorResponse.FieldError.of("body", "<unreadable>", reason);
        final ErrorResponse response = ErrorResponse.of(ErrorCode.NO_BODY, hint, errors);
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    /**
     * javax.validation.Valid or @Validated 으로 binding error 발생시 발생.
     * HttpMessageConverter 에서 등록한 HttpMessageConverter binding 못할경우 발생
     * 주로 @RequestBody, @RequestPart 어노테이션에서 발생
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    protected ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        log.error("Handler exception: {}", e.getMessage(), e);
        final ErrorResponse response = ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE, e.getBindingResult());
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    /**
     * @Validated 가 적용된 controller 의 path/query parameter 검증 실패 시 발생.
     * @Valid @RequestBody 의 MethodArgumentNotValidException 과 달리 ConstraintViolationException 으로 던져진다.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    protected ResponseEntity<?> handleConstraintViolationException(
            ConstraintViolationException e, HttpServletRequest request) {
        String violations = e.getConstraintViolations() == null
                ? e.getMessage()
                : e.getConstraintViolations().stream()
                        .map(this::renderViolation)
                        .collect(Collectors.joining("; "));
        log.warn("Constraint violation: {}", violations);
        final ErrorResponse response = ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE, violations);

        // SSE endpoint (text/event-stream) 에서는 JSON 응답이 Accept 매칭 실패 — SSE 이벤트로 변환.
        // 형식: "event: error\ndata: <json>\n\n" + status 400 + Content-Type: text/event-stream.
        // 일반 endpoint 는 기존 JSON 응답 유지 — 단 SSE endpoint 의 produces 제약을 우회하려면
        // Content-Type 을 명시적으로 application/json 으로 설정해야 함 (그렇지 않으면 controller
        // 의 produces=text/event-stream 가 exception handler 의 content negotiation 까지 영향).
        if (acceptsEventStream(request)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(toSseErrorEvent(response));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    /**
     * SSE 응답이 적절한지 판단:
     * <ol>
     *   <li>Accept 헤더에 text/event-stream 포함 (정상 SSE 클라이언트), 또는
     *   <li>request URI 가 SSE 전용 endpoint 패턴 (.../events) 매칭 — Accept 누락/와일드카드 (*<!---->/*)
     *       케이스에 SSE 응답 강제. 정상 SSE 클라이언트 시나리오 대부분 커버.
     * </ol>
     *
     * <p><b>한계</b>: 클라이언트가 명시적으로 Accept: application/json 을 SSE endpoint 에 보낸 경우는
     * Spring 의 응답 측 content negotiation 이 SSE 응답을 거부 (HttpMediaTypeNotAcceptable)
     * → 500 fallback. 이는 클라이언트의 misconfigured Accept 가 원인이며 handler 차원에서 해결 불가.
     * 정상 SSE 클라이언트 (Bruno / EventSource API) 는 항상 Accept: text/event-stream 을 보냄.
     */
    private static boolean acceptsEventStream(HttpServletRequest request) {
        if (request == null) return false;
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return true;
        }
        String uri = request.getRequestURI();
        return uri != null && uri.endsWith("/events");
    }

    /**
     * SSE 'error' event 직렬화.
     * <pre>
     *   event: error
     *   data: {"code":"INVALID_INPUT_VALUE","message":"...","status":400, ...}
     *
     * </pre>
     * 마지막 빈 줄은 SSE 프로토콜 상 event 종료 마커.
     */
    private String toSseErrorEvent(ErrorResponse response) {
        String json;
        try {
            json = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            // ErrorResponse 직렬화 실패는 거의 불가능 — fallback 으로 단순 키만 표시.
            json = "{\"code\":\"" + response.getCode() + "\",\"message\":\""
                    + response.getMessage().replace("\"", "\\\"") + "\"}";
        }
        return "event: error\ndata: " + json + "\n\n";
    }

    private String renderViolation(ConstraintViolation<?> v) {
        // propertyPath 끝 토큰만 표시 (전체 path 는 잡음). 예: "listVmClusters.provider"
        String path = v.getPropertyPath() == null ? "" : v.getPropertyPath().toString();
        int dot = path.lastIndexOf('.');
        String field = dot < 0 ? path : path.substring(dot + 1);
        return field + " " + v.getMessage();
    }

    /**
     * enum type 일치하지 않아 binding 못할 경우 발생
     * 주로 @RequestParam enum으로 binding 못했을 경우 발생
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    protected ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException e) {
        log.error("Handler exception: {}", e.getMessage(), e);
        final ErrorResponse response = ErrorResponse.of(e);
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    /**
     * 지원하지 않은 HTTP method 호출 할 경우 발생
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    protected ResponseEntity<ErrorResponse> handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException e) {
        log.error("Handler exception: {}", e.getMessage(), e);
        final ErrorResponse response = ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED);
        return new ResponseEntity<>(response, HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * 존재하지 않는 리소스에 접근할 경우 발생 (404 Not Found)
     */
    @ExceptionHandler(NoResourceFoundException.class)
    protected ResponseEntity<ErrorResponse> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn("Resource not found: {}", e.getMessage());
        final ErrorResponse response = ErrorResponse.of(ErrorCode.NOT_FOUND);
        return new ResponseEntity<>(response, HttpStatus.NOT_FOUND);
    }

    /**
     * Authentication 객체가 필요한 권한을 보유하지 않은 경우 발생
     */
    @ExceptionHandler(AccessDeniedException.class)
    protected ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException e) {
        log.error("Handler exception: {}", e.getMessage(), e);
        final ErrorResponse response = ErrorResponse.of(ErrorCode.FORBIDDEN);
        return new ResponseEntity<>(response, HttpStatus.valueOf(ErrorCode.FORBIDDEN.getStatus()));
    }

    /**
     * Authentication 객체가 필요한 권한을 보유하지 않은 경우 발생
     */
    @ExceptionHandler(DuplicateKeyException.class)
    protected ResponseEntity<ErrorResponse> handleDuplicateKeyException(DuplicateKeyException e) {
        log.error("Handler exception: {}", e.getMessage(), e);
        final ErrorResponse response = ErrorResponse.of(ErrorCode.DUPLICATE);
        return new ResponseEntity<>(response, HttpStatus.valueOf(ErrorCode.DUPLICATE.getStatus()));
    }

    // ─── BUSINESS / STATE ────────────────────────────────────────────────────────

    /**
     * Provisioning 도메인 전용 핸들러. {@link CustomException} 상속이므로 Spring 이 더 구체적인
     * 본 핸들러를 선택. transient / permanent 여부를 응답 hint 에 명시하여 클라이언트가 재시도
     * 정책을 결정할 수 있도록 함.
     *
     * <p>신규 provisioning 코드는 {@code TransientProvisioningFailure} / {@code PermanentProvisioningFailure}
     * / {@code StateConflictException} / {@code PulumiExecutionException} 사용 권장.
     * 기존 RuntimeException / IllegalStateException 직접 던지지 말 것.
     */
    @ExceptionHandler(ProvisioningException.class)
    protected ResponseEntity<ErrorResponse> handleProvisioningException(final ProvisioningException e) {
        final ErrorCode errorCode = e.getErrorCode();
        if (e.isTransient()) {
            log.warn("Provisioning transient failure (code={}): {}", errorCode.name(), e.getMessage());
        } else {
            log.error("Provisioning permanent failure (code={}): {}", errorCode.name(), e.getMessage(), e);
        }
        // ofSummarized: 장문 (Pulumi stderr 등) 자동 요약 + detail 보존.
        ErrorResponse response = ErrorResponse.ofSummarized(errorCode, e.getMessage())
                .withHint(
                        e.isTransient()
                                ? "외부 시스템 transient 실패. 자동 재시도 후에도 실패하면 자격증명·CSP 상태 확인."
                                : "영구 실패 — 입력 / 상태 / 권한을 확인하고 수정 후 재시도하세요.");
        return new ResponseEntity<>(response, ErrorResponseFormatter.statusOf(errorCode));
    }

    @ExceptionHandler(CustomException.class)
    protected ResponseEntity<ErrorResponse> handleBusinessException(final CustomException e) {
        log.error("Handler exception: {}", e.getMessage(), e);
        final ErrorCode errorCode = e.getErrorCode();
        // CustomException 의 message 가 ErrorCode default 와 다르면 운영 진단에 더 유용
        // (예: "Credential not found: 5bb1...") → API response 에 그대로 노출.
        // 같거나 비어있으면 default 그대로 유지.
        final String detail = e.getMessage();
        final boolean hasDetail = detail != null && !detail.isBlank() && !detail.equals(errorCode.getMessage());

        ErrorResponse response;
        if (e.getField() != null) {
            response = ErrorResponse.of(
                    e.getErrorCode(), ErrorResponse.FieldError.of(e.getField(), e.getValue(), e.getReason()));
        } else if (hasDetail) {
            //  장문 (Pulumi stderr 등) 은 message=요약 + detail=원문 으로 자동 분할.
            response = ErrorResponse.ofSummarized(errorCode, detail);
        } else {
            response = ErrorResponse.of(errorCode);
        }
        return new ResponseEntity<>(response, HttpStatus.valueOf(errorCode.getStatus()));
    }

    /**
     * cluster-provisioning starter 의 Pulumi 실행 실패 (up/preview/destroy) — 외부 시스템 실패이므로
     * UPSTREAM_FAILED(502). starter 가 host 예외에 의존하지 않도록 ProvisioningExecutionException
     * → host 매핑으로 처리.
     */
    @ExceptionHandler(ProvisioningExecutionException.class)
    protected ResponseEntity<ErrorResponse> handleProvisioningExecutionException(
            final ProvisioningExecutionException e) {
        log.error("Provisioning execution failed: {}", e.getMessage(), e);
        //  장문 (Pulumi stderr 등) 은 message=요약 + detail=원문 으로 자동 분할.
        final ErrorResponse response = ErrorResponse.ofSummarized(ErrorCode.UPSTREAM_FAILED, e.getMessage());
        return new ResponseEntity<>(response, HttpStatus.valueOf(ErrorCode.UPSTREAM_FAILED.getStatus()));
    }

    /**
     * 서비스 레이어에서 잘못된 인자(예: 미지원 kind, 범위 외 값)로 던지는 IllegalArgumentException 을
     * 400 으로 매핑. 일반 RuntimeException 보다 먼저 매칭되도록 별도 핸들러로 등록.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    protected ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        final ErrorResponse response = ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE, e.getMessage());
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    /**
     * — IllegalStateException 을 409 Conflict 로 매핑.
     * 예: addon retry 가 FAILED 외 state 에서 호출, addon 중복 enqueue 등 — 요청 자체는 valid
     * 이지만 현재 리소스 state 와 충돌. 400 (bad input) 과 의미 구분.
     *
     * <p>신규 코드는 의미 명확한
     * {@link com.aipaas.anycloud.common.error.exception.provisioning.StateConflictException}
     * 사용 권장. 본 핸들러는 기존 IllegalStateException 호환성 유지용 — 점진 마이그레이션 대상.
     */
    @ExceptionHandler(IllegalStateException.class)
    protected ResponseEntity<ErrorResponse> handleIllegalStateException(IllegalStateException e) {
        log.warn("Illegal state: {}", e.getMessage());
        //  이전엔 body 에 INVALID_INPUT_VALUE(400) 를 넣고 HTTP 만 409 — status 불일치 +
        // "입력 값 오류" 오해 유발. 전용 STATE_CONFLICT(409) 로 HTTP==body 일치 보장.
        final ErrorResponse response = ErrorResponse.ofSummarized(ErrorCode.STATE_CONFLICT, e.getMessage());
        return new ResponseEntity<>(response, HttpStatus.CONFLICT);
    }

    /**
     * — JPA 의 jakarta.persistence.EntityNotFoundException (자체 정의 클래스
     * 와는 별개) 도 404 로 매핑. AddonService / 일반 JPA flow 에서 throw.
     */
    @ExceptionHandler(jakarta.persistence.EntityNotFoundException.class)
    protected ResponseEntity<ErrorResponse> handleJpaEntityNotFoundException(
            jakarta.persistence.EntityNotFoundException e) {
        log.warn("JPA entity not found: {}", e.getMessage());
        final ErrorResponse response = ErrorResponse.of(ErrorCode.ENTITY_NOT_FOUND, e.getMessage());
        return new ResponseEntity<>(response, HttpStatus.NOT_FOUND);
    }

    /**
     * Controller 가 의도적으로 던진 status (503 graceful degradation 등) 를 그대로 전달.
     * 본 핸들러 없으면 generic Exception 핸들러가 잡아 500 으로 변질 — 운영 진단 정보 손실.
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    protected ResponseEntity<ErrorResponse> handleResponseStatusException(
            org.springframework.web.server.ResponseStatusException e) {
        log.warn("Response status exception: {} {}", e.getStatusCode(), e.getReason());
        final ErrorResponse response = ErrorResponse.of(
                ErrorCode.INTERNAL_SERVER_ERROR, e.getReason() != null ? e.getReason() : e.getMessage());
        return new ResponseEntity<>(response, e.getStatusCode());
    }

    @ExceptionHandler(Exception.class)
    protected ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Handler exception: {}", e.getMessage(), e);
        final ErrorResponse response = ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR);
        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    protected ResponseEntity<ErrorResponse> handleEntityNotFoundException(EntityNotFoundException e) {
        log.warn("Entity not found: {}", e.getMessage());
        final ErrorResponse response = ErrorResponse.of(ErrorCode.ENTITY_NOT_FOUND, e.getMessage());
        // statusOf 로 통일한다. 하드코딩하면 ErrorCode 의 status 와 어긋나 body 만 다른 값을 갖는다.
        return new ResponseEntity<>(response, ErrorResponseFormatter.statusOf(ErrorCode.ENTITY_NOT_FOUND));
    }

    /**
     * 클러스터를 찾을 수 없을 때 발생하는 예외 처리
     */
    @ExceptionHandler(ClusterNotFoundException.class)
    protected ResponseEntity<ErrorResponse> handleClusterNotFoundException(ClusterNotFoundException e) {
        log.warn("Cluster not found: {}", e.getMessage(), e);
        //  복구 경로 제공 — 목록에서 정확한 이름 확인. 예외가 clusterName 을 들고 있어
        // 정적이 아닌 정확한 link 생성 가능.
        final ErrorResponse response = ErrorResponse.of(e.getErrorCode(), e.getMessage())
                .withHint("GET /v1/clusters 로 등록된 cluster 이름을 확인하세요.")
                .withLinks(java.util.Map.of("clusterList", "/v1/clusters"));
        return new ResponseEntity<>(response, HttpStatus.NOT_FOUND);
    }

    /**
     * Helm Repository를 찾을 수 없을 때 발생하는 예외 처리
     */
    @ExceptionHandler(HelmRepositoryNotFoundException.class)
    protected ResponseEntity<ErrorResponse> handleHelmRepositoryNotFoundException(HelmRepositoryNotFoundException e) {
        log.warn("Helm repository not found: {}", e.getMessage(), e);
        final ErrorResponse response = ErrorResponse.of(e.getErrorCode(), e.getMessage());
        return new ResponseEntity<>(response, HttpStatus.NOT_FOUND);
    }

    /**
     * Helm Chart를 찾을 수 없을 때 발생하는 예외 처리
     */
    @ExceptionHandler(HelmChartNotFoundException.class)
    protected ResponseEntity<ErrorResponse> handleHelmChartNotFoundException(HelmChartNotFoundException e) {
        log.warn("Helm chart not found: {}", e.getMessage(), e);
        final ErrorResponse response = ErrorResponse.of(e.getErrorCode(), e.getMessage());
        return new ResponseEntity<>(response, HttpStatus.NOT_FOUND);
    }

    /**
     * Helm Chart 배포 실패 시 발생하는 예외 처리
     */
    @ExceptionHandler(HelmDeploymentException.class)
    protected ResponseEntity<ErrorResponse> handleHelmDeploymentException(HelmDeploymentException e) {
        log.error("Helm deployment failed: {}", e.getMessage(), e);
        final ErrorResponse response = ErrorResponse.of(e.getErrorCode(), e.getMessage());
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    /**
     * Agent 의 RESTMapper 가 입력 kind 를 resolve 못한 경우.
     *
     * <p>404 + metadata 에 {@code input} 과 {@code suggestions} (Levenshtein top-3) 노출.
     * caller 는 type-ahead UI 에서 suggestions 활용 또는 사용자에게 오타 보정 제시.
     */
    @ExceptionHandler(UnsupportedKindException.class)
    protected ResponseEntity<ErrorResponse> handleUnsupportedKind(UnsupportedKindException e) {
        log.info("Unsupported kind: input={}, suggestions={}", e.input(), e.suggestions());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("input", e.input());
        meta.put("suggestions", e.suggestions());
        final ErrorResponse response = ErrorResponse.of(ErrorCode.UNSUPPORTED_KIND, e.getMessage(), meta);
        return new ResponseEntity<>(response, HttpStatus.NOT_FOUND);
    }

    // ==== starter exception 일원화 ====
    // 응답 envelope 는 {@link ApiSuccessResponse} (success=false) 유지 — frontend 호환성 보존
    // (UI 가 success boolean 으로 분기).

    /**
     * cluster-observability-starter 의 {@link ObservabilityException} — Prometheus / Alertmanager /
     * Grafana 라우팅 실패. errorCode 별 HTTP status 매핑.
     */
    @ExceptionHandler(ObservabilityException.class)
    public ResponseEntity<ApiSuccessResponse<Void>> handleObservability(ObservabilityException e) {
        HttpStatus status =
                switch (e.errorCode()) {
                    case "NO_ACTIVE_AGENT", "AGENT_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
                    case "TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
                    case "GRAFANA_NOT_EXPOSED" -> HttpStatus.NOT_FOUND;
                    case "PERMISSION_DENIED",
                            "NAMESPACE_NOT_ALLOWED",
                            "CHART_NOT_ALLOWED",
                            "VERSION_OUT_OF_RANGE" -> HttpStatus.FORBIDDEN;
                    case "MISSING_QUERY", "MISSING_PARAM", "INVALID_VALUES" -> HttpStatus.BAD_REQUEST;
                    default -> HttpStatus.BAD_GATEWAY;
                };
        log.warn("Observability failed: code={}, msg={}", e.errorCode(), e.getMessage());
        ApiSuccessResponse<Void> body = new ApiSuccessResponse<>(
                false, status.value(), "[" + e.errorCode() + "] " + e.getMessage(), null, null, null);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * cluster-backup-starter 의 {@link BackupException} — K8s upgrade / Velero / etcd backup
     * 등 lifecycle 작업 실패. errorCode 별 HTTP status 매핑.
     */
    @ExceptionHandler(BackupException.class)
    public ResponseEntity<ApiSuccessResponse<Void>> handleLifecycle(BackupException e) {
        HttpStatus status =
                switch (e.errorCode()) {
                    case "NO_ACTIVE_AGENT", "NO_NODE_AGENT" -> HttpStatus.SERVICE_UNAVAILABLE;
                    case "TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
                    case "INVALID_PARAMS", "MISSING_PARAM" -> HttpStatus.BAD_REQUEST;
                    case "DRAIN_FAILED",
                            "NODE_AGENT_RPC_FAILED",
                            "NODE_NOT_READY",
                            "UPGRADE_PLAN_FAILED",
                            "UPGRADE_APPLY_FAILED" -> HttpStatus.BAD_GATEWAY;
                    default -> HttpStatus.INTERNAL_SERVER_ERROR;
                };
        log.warn("Lifecycle failed: code={}, msg={}", e.errorCode(), e.getMessage());
        ApiSuccessResponse<Void> body = new ApiSuccessResponse<>(
                false, status.value(), "[" + e.errorCode() + "] " + e.getMessage(), null, null, null);
        return ResponseEntity.status(status).body(body);
    }
}
