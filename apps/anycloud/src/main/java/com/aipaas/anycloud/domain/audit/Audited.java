package com.aipaas.anycloud.domain.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 붙여 두면 {@link AuditAspect} 가 호출 전후를 audit_log 에 남깁니다. controller 가 아니라 service
 * method 에 거는 이유는 scheduler 나 gRPC 핸들러처럼 HTTP 를 거치지 않는 호출도 잡기 위해서입니다.
 *
 * <p>{@code resourceId} 와 {@code summary} 는 SpEL 입니다. 쓸 수 있는 건 method 의 파라미터 이름,
 * 반환값 {@code #result}, 던져진 예외 {@code #exception} 입니다. 예외가 나면 {@code #result} 가,
 * 성공하면 {@code #exception} 이 null 이므로 양쪽 다 쓰는 식이면 null 을 다뤄야 합니다.
 *
 * <pre>
 * &#64;Audited(action = "agentCert.revoke", resourceType = "clusterAgent",
 *          resourceId = "#clusterName",
 *          summary = "'reason=' + (#reason ?: 'none') + ', revoked=' + #result")
 * public int revokeCluster(String clusterName, String reason) { ... }
 * </pre>
 *
 * <p>예외가 나면 statusCode 500 으로 기록하고 그대로 다시 던집니다. audit 실패가 비즈니스 로직을
 * 막지 않습니다.
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
