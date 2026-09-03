package com.aipaas.anycloud.domain.audit;

import io.aipaas.cluster.agent.identity.ImpersonationContext;
import io.aipaas.cluster.agent.identity.ImpersonationIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@link Audited} 처리 aspect.
 *
 * <p>Around advice — 성공 시 statusCode=200 + #result 평가, 예외 시 statusCode=500 +
 * errorMessage 기록. SpEL 평가 실패는 swallow (audit 은 best-effort).
 *
 * <p>Method args 는 {@link DefaultParameterNameDiscoverer} 로 이름 추출 (Java 8+ {@code -parameters}
 * 컴파일 옵션 필요). Spring Boot starter 가 기본 활성화.
 */
@Slf4j
@Aspect
@Component
public class AuditAspect {

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAM_DISCOVERER = new DefaultParameterNameDiscoverer();

    private final AuditLogger auditLogger;
    /**
     * Impersonation 활성화 시 audit 의 principal 자동 채우기. starter 가
     * default ThreadLocalImpersonationContext bean 을 등록 → 항상 주입 가능 (toggle OFF 면 current()
     * 가 empty Optional 반환). ObjectProvider 로 받아 bean 부재 시도 안전.
     */
    private final ObjectProvider<ImpersonationContext> impersonationContextProvider;

    public AuditAspect(AuditLogger auditLogger, ObjectProvider<ImpersonationContext> impersonationContextProvider) {
        this.auditLogger = auditLogger;
        this.impersonationContextProvider = impersonationContextProvider;
    }

    @Around("@annotation(audited)")
    public Object record(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        Object result = null;
        Throwable error = null;
        long startNanos = System.nanoTime();
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            try {
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
                EvaluationContext ctx = buildContext(pjp, result, error);
                String resourceId = evalOrNull(audited.resourceId(), ctx);
                String summary = evalOrNull(audited.summary(), ctx);

                // HTTP request 가 있으면 method/path/clientIp 추출 (controller-initiated).
                // 없으면 null (scheduler / @Async 등 service-initiated audit — ).
                HttpRequestSnapshot http = currentRequestSnapshot();
                // Impersonation 활성화 시 ImpersonationInterceptor 가 ThreadLocal 에
                // identity 를 set 한 상태. principal = user. toggle OFF 또는 system/async 경로면 null
                // ().
                String principal = currentPrincipalOrNull();

                auditLogger.record(AuditEntry.builder()
                        .principal(principal)
                        .action(audited.action())
                        .resourceType(audited.resourceType())
                        .resourceId(resourceId)
                        .statusCode(error == null ? 200 : 500)
                        .errorMessage(error == null ? null : error.getMessage())
                        .durationMs(durationMs)
                        .requestSummary(summary)
                        .httpMethod(http == null ? null : http.method())
                        .path(http == null ? null : http.path())
                        .clientIp(http == null ? null : http.clientIp())
                        .build());
            } catch (Exception e) {
                // audit 실패는 비즈니스에 영향 X.
                log.warn(
                        "AuditAspect: failed to record audit for {}: {}",
                        pjp.getSignature().toShortString(),
                        e.toString());
            }
        }
    }

    private static EvaluationContext buildContext(ProceedingJoinPoint pjp, Object result, Throwable error) {
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        // Method args by name.
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        String[] names = PARAM_DISCOVERER.getParameterNames(method);
        Object[] args = pjp.getArgs();
        if (names != null) {
            for (int i = 0; i < names.length && i < args.length; i++) {
                ctx.setVariable(names[i], args[i]);
            }
        }
        // Result + exception.
        ctx.setVariable("result", result);
        ctx.setVariable("exception", error);
        return ctx;
    }

    private static String evalOrNull(String spel, EvaluationContext ctx) {
        if (spel == null || spel.isBlank()) {
            return null;
        }
        try {
            Expression exp = PARSER.parseExpression(spel);
            Object value = exp.getValue(ctx);
            return value == null ? null : value.toString();
        } catch (Exception e) {
            log.debug("AuditAspect: SpEL eval failed: {} ({})", spel, e.toString());
            return null;
        }
    }

    /**
     * 현재 thread 에 HTTP request attributes 가 binding 되어 있으면 method/path/clientIp 추출.
     * scheduler / @Async / gRPC 핸들러 등 controller 외에서 호출되면 null.
     *
     * <p>X-Forwarded-For / X-Real-IP 헤더 우선 — gateway / LB 뒤 backend 에서 실제 client IP 보존.
     */
    private static HttpRequestSnapshot currentRequestSnapshot() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes sra)) {
            return null; // not in HTTP request thread
        }
        HttpServletRequest req = sra.getRequest();
        String ip = req.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) {
            int comma = ip.indexOf(',');
            ip = (comma > 0 ? ip.substring(0, comma) : ip).trim();
        } else {
            String real = req.getHeader("X-Real-IP");
            ip = (real != null && !real.isBlank()) ? real.trim() : req.getRemoteAddr();
        }
        return new HttpRequestSnapshot(req.getMethod(), req.getRequestURI(), ip);
    }

    private record HttpRequestSnapshot(String method, String path, String clientIp) {}

    /**
     * 현재 thread 에 impersonation identity 가 set 되어 있으면 user (= K8s username) 반환.
     * toggle OFF / async 경로 / interceptor 미통과 시 null —  column.
     *
     * <p>fail-open: provider 부재나 current() 호출 실패 모두 swallow (audit 은 best-effort, 비즈니스
     * 흐름에 영향 X).
     */
    private String currentPrincipalOrNull() {
        try {
            ImpersonationContext ctx = impersonationContextProvider.getIfAvailable();
            if (ctx == null) return null;
            Optional<ImpersonationIdentity> current = ctx.current();
            return current.map(ImpersonationIdentity::user)
                    .filter(s -> !s.isBlank())
                    .orElse(null);
        } catch (RuntimeException e) {
            log.debug("AuditAspect: principal lookup failed (non-fatal): {}", e.toString());
            return null;
        }
    }
}
