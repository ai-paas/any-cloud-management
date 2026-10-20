package com.aipaas.anycloud.domain.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 적용 시 {@link AuditAspect} 가 호출 전후를 audit_log 에 기록. controller 가 아닌 service
 * method 대상인 이유는 scheduler, gRPC 핸들러처럼 HTTP 를 경유하지 않는 호출까지 포함하기 위함.
 *
 * <p>{@code resourceId} 와 {@code summary} 는 SpEL. 사용 가능한 값은 method 파라미터 이름,
 * 반환값 {@code #result}, 발생 예외 {@code #exception}. 예외 발생 시 {@code #result} 가,
 * 성공 시 {@code #exception} 이 null 이므로 양쪽을 함께 쓰는 식은 null 처리 필요.
 *
 * <pre>
 * &#64;Audited(action = "agentCert.revoke", resourceType = "clusterAgent",
 *          resourceId = "#clusterName",
 *          summary = "'reason=' + (#reason ?: 'none') + ', revoked=' + #result")
 * public int revokeCluster(String clusterName, String reason) { ... }
 * </pre>
 *
 * <p>예외 발생 시 statusCode 500 으로 기록 후 재전파. audit 실패가 비즈니스 로직을 차단하지 않음.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** Audit log 의 action: 표준 명명: {@code <domain>.<verb>}. 예: {@code "agentCert.revoke"}. */
    String action();

    /** Audit log 의 resourceType: 보통 domain (예: {@code "clusterAgent"}, {@code "backendCa"}). */
    String resourceType();

    /** SpEL 표현. 보통 {@code "#clusterName"} 같은 path variable. blank 이면 null. */
    String resourceId() default "";

    /** SpEL 표현. {@code "'count=' + #result"} 같은 summary 문자열. blank 이면 null. */
    String summary() default "";
}
