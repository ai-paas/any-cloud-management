package com.aipaas.anycloud.domain.kube.internal;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.util.Locale;

/**
 * K8s 호출 실패의 cause chain 을 분석해 UX 친화적 reason code 로 분류하는 정적 utility.
 *
 * <p>{@code KubeServiceImpl} 의 642 LOC god class 에서 분리. ~95 LOC 의 순수
 * 정적 helper 묶음으로, Spring bean 없이 어디서든 import 해 사용 가능.
 *
 * <p>본 분류기는 {@link io.github.resilience4j.circuitbreaker.CircuitBreaker} fallback path 에서
 * caller (UI / 운영자) 가 "agent 가 죽음 vs RBAC 거부 vs 알 수 없는 kind" 등을 즉시 구분할 수 있도록
 * 표준 reason code 를 제공.
 *
 * <p>cause unwrap 로직 유지 — wrapper exception 의 message 가 root cause 의
 * 진짜 키워드를 가리지 않도록 모든 cause level 의 message 를 누적 후 keyword scan.
 *
 * @see com.aipaas.anycloud.domain.kube.internal.KubeServiceImpl
 */
public final class KubeErrorClassifier {

    private KubeErrorClassifier() {
        // static utility
    }

    /** Cause chain max depth — 순환 reference 방어. */
    private static final int CAUSE_UNWRAP_GUARD = 16;

    /**
     * Throwable 의 cause chain 을 unwrap 해 표준 reason code + 운영자용 message 로 분류.
     *
     * <p>우선순위:
     * <ol>
     *   <li>{@code CIRCUIT_OPEN} — circuit breaker OPEN (연속 실패로 일시 차단)</li>
     *   <li>{@code NAMESPACE_NOT_ALLOWED} — agent 의 namespace allowlist 미허용</li>
     *   <li>{@code RESOURCE_KIND_DENIED} — agent ResourcePolicy 가 kind 거부</li>
     *   <li>{@code UNSUPPORTED_KIND} — RESTMapper 가 kind resolve 실패 (CRD 미설치/오타/RBAC)</li>
     *   <li>{@code FORBIDDEN} — agent SA RBAC 가 해당 GVR list/get 권한 부족 (K8s 403)</li>
     *   <li>{@code AGENT_INACTIVE} — cluster agent 가 backend 에 connect 안 됨</li>
     *   <li>{@code AGENT_ERROR} — 그 외 (RPC error / timeout 등)</li>
     * </ol>
     */
    public static DegradedInfo classify(Throwable t) {
        if (t instanceof CallNotPermittedException) {
            return new DegradedInfo(
                    "CIRCUIT_OPEN", "Circuit breaker OPEN — temporary block after repeated failures. Retry in 30s.");
        }
        // 모든 cause level 의 message 누적 — wrapper 가 root cause 정보를 가리는 것 방지.
        // e.g. CustomException("Cluster agent unavailable ... : <real cause>") → 두 메시지 모두 검사.
        String aggregated = aggregateCauseMessages(t).toLowerCase(Locale.ROOT);
        String rootMsg = rootCauseMessage(t);

        // 우선순위 — 구체적 root cause 가 wrapper 의 일반 키워드를 이긴다.
        if (aggregated.contains("namespace_not_allowed") || aggregated.contains("not in allowlist")) {
            return new DegradedInfo(
                    "NAMESPACE_NOT_ALLOWED",
                    "Agent allowlist does not include this namespace. Add namespace to agent's allowed list.");
        }
        if (aggregated.contains("resource_kind_denied")) {
            return new DegradedInfo(
                    "RESOURCE_KIND_DENIED",
                    "Agent resource policy denies this kind (or kind+namespace pair). "
                            + "Update agent ConfigMap 의 resource_policy section.");
        }
        if (aggregated.contains("unsupported_kind") || aggregated.contains("unsupported kind")) {
            return new DegradedInfo(
                    "UNSUPPORTED_KIND",
                    "Cluster discovery API does not expose this kind. Verify spelling or CRD installation. "
                            + "kubectl api-resources 로 사용 가능한 kind 확인 가능.");
        }
        // RBAC denial (K8s 403). agent SA 의 ClusterRole 이 해당 GVR get/list verb 부족할 때.
        if (aggregated.contains("forbidden")
                || aggregated.contains("permission_denied")
                || aggregated.contains("cannot list")
                || aggregated.contains("cannot get")) {
            return new DegradedInfo(
                    "FORBIDDEN",
                    "Agent ServiceAccount lacks RBAC for this resource. "
                            + "Verify agent ClusterRole grants get/list/watch on the kind's apiGroup. "
                            + "Diagnose: kubectl auth can-i list <kind> --as=system:serviceaccount:<ns>:<agent-sa>.");
        }
        // AGENT_INACTIVE — root cause 가 없는 경우만. 즉 requireAgent() 가 cause=null 로 throw 한
        // 직접 경로 또는 root msg 자체가 명시적으로 session 부재를 가리킬 때.
        if (rootMsg.toLowerCase(Locale.ROOT).contains("no active session")
                || aggregated.contains("no active session")) {
            return new DegradedInfo(
                    "AGENT_INACTIVE", "Cluster agent not connected. Install agent or verify connectivity.");
        }
        // wrapper-only "agent unavailable" — cause 가 없을 때만 INACTIVE 로 간주.
        if (t != null && t.getCause() == null && aggregated.contains("agent unavailable")) {
            return new DegradedInfo(
                    "AGENT_INACTIVE", "Cluster agent not connected. Install agent or verify connectivity.");
        }
        // Default — root cause message 노출 (wrapper 가 아닌 진짜 원인 노출).
        return new DegradedInfo("AGENT_ERROR", rootMsg.isBlank() ? "Agent call failed" : rootMsg);
    }

    /** Throwable + 모든 cause 의 message 를 공백 join — wrapper 가 root 의 키워드를 가리지 않도록. */
    private static String aggregateCauseMessages(Throwable t) {
        if (t == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int guard = 0;
        while (cur != null && guard++ < CAUSE_UNWRAP_GUARD) {
            if (cur.getMessage() != null) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(cur.getMessage());
            }
            Throwable next = cur.getCause();
            if (next == cur) {
                break;
            }
            cur = next;
        }
        return sb.toString();
    }

    /** Cause chain 의 가장 안쪽 Throwable 의 message. wrapper 제거된 진짜 원인. */
    private static String rootCauseMessage(Throwable t) {
        if (t == null) {
            return "";
        }
        Throwable cur = t;
        int guard = 0;
        while (cur.getCause() != null && cur.getCause() != cur && guard++ < CAUSE_UNWRAP_GUARD) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? "" : cur.getMessage();
    }

    /** kind 라벨 정규화 — lowercase, blank → "unknown". Micrometer 카디널리티 폭증 방지 위해 trim. */
    public static String normalizeKindLabel(String kind) {
        if (kind == null) {
            return "unknown";
        }
        String t = kind.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? "unknown" : t;
    }

    /**
     * Classify 결과 — reason code + 운영자용 actionable message.
     *
     * @param reason 표준 분류 코드 (CIRCUIT_OPEN / FORBIDDEN / ... / AGENT_ERROR)
     * @param message 운영자 / UI 에 노출되는 진단 hint
     */
    public record DegradedInfo(String reason, String message) {}
}
