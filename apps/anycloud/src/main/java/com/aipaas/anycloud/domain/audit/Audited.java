package com.aipaas.anycloud.domain.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * service method 의 audit 자동화.
 *
 * <p>본 annotation 을 service-method 에 부여하면 {@link AuditAspect} 가 method 호출 전후로 자동 기록.
 * 내부 callers (scheduler / gRPC 핸들러 등) 도 동일하게 capture — 우회 불가.
 *
 * <p>Resource ID / summary 는 SpEL 로 method args / return value 참조. 예:
 * <pre>
 * &#64;Audited(action = "agentCert.revoke", resourceType = "clusterAgent",
 *          resourceId = "#clusterName",
 *          summary = "'reason=' + (#reason ?: 'none') + ', revoked=' + #result")
 * public int revokeCluster(String clusterName, String reason) { ... }
 * </pre>
 *
 * <p>SpEL 컨텍스트:
 * <ul>
 *   <li>method 의 named parameters (e.g., {@code #clusterName}, {@code #reason})</li>
 *   <li>{@code #result} — method 반환값 (예외 시 null)</li>
 *   <li>{@code #exception} — Throwable (성공 시 null)</li>
 * </ul>
 *
 * <p>예외 발생 시: statusCode=500 으로 기록 + 예외 그대로 re-throw (audit 은 best-effort,
 * 비즈니스 로직 영향 X).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** Audit log 의 action — 표준 명명: {@code <domain>.<verb>}. 예: {@code "agentCert.revoke"}. */
    String action();

    /** Audit log 의 resourceType — 보통 domain (예: {@code "clusterAgent"}, {@code "backendCa"}). */
    String resourceType();

    /** SpEL 표현. 보통 {@code "#clusterName"} 같은 path variable. blank 이면 null. */
    String resourceId() default "";

    /** SpEL 표현. {@code "'count=' + #result"} 같은 summary 문자열. blank 이면 null. */
    String summary() default "";
}
