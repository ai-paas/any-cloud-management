package com.aipaas.anycloud.domain.agent.policy;

import io.aipaas.cluster.agent.runtime.AgentPolicySnapshot;
import io.aipaas.cluster.agent.runtime.AgentPolicySnapshot.ResourcePolicy;
import io.aipaas.cluster.agent.runtime.AgentPolicySnapshot.ResourceRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Agent 의 현재 적용된 policy snapshot 에 대한 일관성/위험 검증. 운영자가 ConfigMap edit 후 즉시
 * 잘못된 설정을 발견할 수 있도록 backend 가 추가하는 layer.
 *
 * <p>참고 룰북: {@code docs/runbooks/cluster-agent-resource-policy.md}.
 *
 * <p>Severity 4 단계:
 * <ul>
 *   <li>HIGH    — 즉시 위험 (secret 유출 등). 조치 권장</li>
 *   <li>MEDIUM  — 잠재 위험 또는 misconfiguration (typo 등)</li>
 *   <li>LOW     — non-default 선택. 의도 확인 권장</li>
 *   <li>INFO    — 참고 정보 (wildcard 사용 중 등)</li>
 * </ul>
 */
@Component
public class AgentPolicyValidator {

    /** 권장 default deny — 운영 가이드 docs/runbooks/cluster-agent-resource-policy.md 참고. */
    private static final Set<String> RECOMMENDED_DENY = Set.of(
            "secrets",
            "roles",
            "rolebindings",
            "clusterroles",
            "clusterrolebindings",
            "validatingwebhookconfigurations",
            "mutatingwebhookconfigurations");

    /**
     * backend 가 agent 의 ConfigMap 을 갱신하려면 반드시 살아있어야 하는 명령.
     * 사용자가 allowedCommands 에 이 셋 중 하나라도 빠뜨리면 다음 APPLY_AGENT_CONFIG 호출이
     * PERMISSION_DENIED — 자기 발 잘라 backend 가 더 이상 정책을 못 고침 (kubectl patch 만 escape).
     *
     * <ul>
     *   <li>APPLY_AGENT_CONFIG — admin PUT /agent-policy 의 핵심 RPC</li>
     *   <li>GET_AGENT_CONFIG — diff 계산 (before-snapshot fetch)</li>
     *   <li>ENSURE_AGENT_CONFIG_ANNOTATIONS — backend full-policy push 시 annotation 보장</li>
     * </ul>
     */
    public static final Set<String> REQUIRED_MANAGEMENT_COMMANDS =
            Set.of("APPLY_AGENT_CONFIG", "GET_AGENT_CONFIG", "ENSURE_AGENT_CONFIG_ANNOTATIONS");

    /** Common plural 형태 (lowercase) — 정확한 list 가 아니라 휴리스틱 (RESTMapper 가 source-of-truth). */
    private static final Set<String> KNOWN_NON_PLURAL = Set.of(
            "pod",
            "service",
            "deployment",
            "statefulset",
            "daemonset",
            "replicaset",
            "configmap",
            "secret",
            "namespace",
            "node",
            "persistentvolume",
            "persistentvolumeclaim",
            "job",
            "cronjob",
            "ingress",
            "endpoint",
            "event",
            "role",
            "rolebinding",
            "clusterrole",
            "clusterrolebinding",
            "storageclass",
            "customresourcedefinition",
            "serviceaccount",
            "horizontalpodautoscaler");

    /**
     * request body 의 allowedCommands 가 backend 의 self-management 를 가능
     * 케 하는지 검증. 누락된 명령이 있으면 HIGH severity warning 반환. caller (controller) 가 force
     * 없이는 422 로 reject 권장.
     *
     * <p>이 검증은 snapshot ({@link #validate}) 검증과 별개 — body 가 agent 에 적용되기 전 단계라
     * snapshot 이 아직 없는 시점에 호출. Empty / null input 은 빈 list 반환 (caller 가 다른 path 에서 처리).
     */
    public List<PolicyWarning> validateRequestCommands(List<String> requestedCommands) {
        List<PolicyWarning> out = new ArrayList<>();
        if (requestedCommands == null || requestedCommands.isEmpty()) {
            return out;
        }
        Set<String> upper = new java.util.HashSet<>();
        for (String c : requestedCommands) {
            if (c != null && !c.isBlank()) {
                upper.add(c.trim().toUpperCase(Locale.ROOT));
            }
        }
        // wildcard "*" 면 모든 명령 허용 — 통과.
        if (upper.contains("*")) {
            return out;
        }
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED_MANAGEMENT_COMMANDS) {
            if (!upper.contains(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            out.add(new PolicyWarning(
                    Severity.HIGH,
                    "MISSING_MANAGEMENT_COMMANDS",
                    "allowedCommands 에서 backend self-management 명령이 빠짐: " + missing
                            + ". 적용 시 backend 가 다음 APPLY_AGENT_CONFIG 호출에서 PERMISSION_DENIED — "
                            + "kubectl patch 만으로 escape 가능. force=true 로 강제 적용하려면 이해 후 진행."));
        }
        return out;
    }

    public List<PolicyWarning> validate(AgentPolicySnapshot snapshot) {
        List<PolicyWarning> out = new ArrayList<>();
        if (snapshot == null) {
            return out;
        }

        // 1. wildcard namespace — info 단계 (멀티테넌트 환경이면 위험)
        if (snapshot.allowAllNamespaces()) {
            out.add(new PolicyWarning(
                    Severity.INFO,
                    "WILDCARD_NAMESPACES",
                    "allowed_namespaces 가 wildcard ('*') 입니다. 모든 namespace 허용. " + "멀티 테넌트 환경이면 명시 list 권장."));
        }

        ResourcePolicy policy = snapshot.resourcePolicy();
        if (policy == null) {
            out.add(new PolicyWarning(
                    Severity.INFO,
                    "NO_RESOURCE_POLICY",
                    "resource_policy section 미설정. RBAC + namespace allowlist 만 적용. "
                            + "보안 강화 원하면 docs/runbooks/cluster-agent-resource-policy.md 참고."));
            return out;
        }

        String mode = policy.mode() == null ? "" : policy.mode().toLowerCase(Locale.ROOT);
        switch (mode) {
            case "allow_all_discovered" -> validateAllowAllDiscovered(policy, out);
            case "strict" -> validateStrict(policy, out);
            default -> out.add(new PolicyWarning(
                    Severity.MEDIUM,
                    "UNKNOWN_MODE",
                    "resource_policy.mode 가 알 수 없는 값: '" + policy.mode() + "'. "
                            + "'allow_all_discovered' 또는 'strict' 권장."));
        }

        // 공통: plural typo 검출
        validatePlurals(policy.deny(), "deny", out);
        validatePlurals(policy.allow(), "allow", out);

        return out;
    }

    private void validateAllowAllDiscovered(ResourcePolicy policy, List<PolicyWarning> out) {
        Set<String> deniedKinds = new java.util.HashSet<>();
        for (ResourceRule r : policy.deny()) {
            if (r.namespace() == null || r.namespace().isBlank()) {
                deniedKinds.add(r.kind() == null ? "" : r.kind().toLowerCase(Locale.ROOT));
            }
        }
        // HIGH — secrets 글로벌 deny 누락
        if (!deniedKinds.contains("secrets")) {
            out.add(new PolicyWarning(
                    Severity.HIGH,
                    "MISSING_SECRETS_DENY",
                    "secrets 가 global deny list 에 없습니다. allow_all_discovered 모드에서 모든 namespace 의 "
                            + "secrets 가 노출됩니다. 멀티 테넌트 또는 사용자 facing 환경에서는 'kind: secrets' 추가 권장."));
        }
        // MEDIUM — RBAC kinds deny 누락 (있어야 하나 강제 아님)
        List<String> missingRbac = new ArrayList<>();
        for (String rbac : List.of("roles", "rolebindings", "clusterroles", "clusterrolebindings")) {
            if (!deniedKinds.contains(rbac)) {
                missingRbac.add(rbac);
            }
        }
        if (!missingRbac.isEmpty()) {
            out.add(new PolicyWarning(
                    Severity.MEDIUM,
                    "MISSING_RBAC_DENY",
                    "RBAC kinds (" + String.join(", ", missingRbac) + ") 가 deny list 에 없습니다. "
                            + "일반 사용자가 RBAC 변경 시 권한 escalation 가능."));
        }
        // MEDIUM — webhook configs deny 누락
        List<String> missingWebhooks = new ArrayList<>();
        for (String w : List.of("validatingwebhookconfigurations", "mutatingwebhookconfigurations")) {
            if (!deniedKinds.contains(w)) {
                missingWebhooks.add(w);
            }
        }
        if (!missingWebhooks.isEmpty()) {
            out.add(new PolicyWarning(
                    Severity.MEDIUM,
                    "MISSING_WEBHOOK_DENY",
                    "webhook configs (" + String.join(", ", missingWebhooks) + ") 가 deny 에 없습니다. "
                            + "악의적 변조 시 admission control 우회 가능."));
        }
        // INFO — kube-system 보호 누락
        boolean kubeSysProtected = policy.deny().stream().anyMatch(r -> "kube-system".equals(r.namespace()));
        if (!kubeSysProtected) {
            out.add(new PolicyWarning(
                    Severity.LOW,
                    "KUBE_SYSTEM_UNPROTECTED",
                    "kube-system 의 자원에 대한 deny rule 이 없습니다. 시스템 자원이 사용자에게 노출되는 "
                            + "경우 운영 위험. 권장 deny: 'kind: configmaps, namespace: kube-system' 등."));
        }
    }

    private void validateStrict(ResourcePolicy policy, List<PolicyWarning> out) {
        if (policy.allow() == null || policy.allow().isEmpty()) {
            out.add(new PolicyWarning(
                    Severity.HIGH,
                    "STRICT_EMPTY_ALLOW",
                    "mode=strict 인데 allow 가 비어있습니다. 모든 K8s 작업이 차단됩니다. " + "emergency lock-down 이 아니라면 의도 확인 필요."));
        }
        if (policy.deny() != null && !policy.deny().isEmpty()) {
            out.add(new PolicyWarning(
                    Severity.LOW,
                    "STRICT_DENY_REDUNDANT",
                    "mode=strict 에서 deny 는 의미가 없습니다 (allow 외 모든 kind 가 자동 거부). " + "deny 항목 제거 권장 — 정책 의도를 명확히 표현."));
        }
        // 권장 RBAC kinds 가 allow 에 포함되어 있는지 — 있으면 INFO (의도된 허용일 수 있음)
        Set<String> allowed = new java.util.HashSet<>();
        for (ResourceRule r : policy.allow()) {
            allowed.add(r.kind() == null ? "" : r.kind().toLowerCase(Locale.ROOT));
        }
        List<String> sensitiveAllowed = new ArrayList<>();
        for (String s : RECOMMENDED_DENY) {
            if (allowed.contains(s)) {
                sensitiveAllowed.add(s);
            }
        }
        if (!sensitiveAllowed.isEmpty()) {
            out.add(new PolicyWarning(
                    Severity.LOW,
                    "SENSITIVE_KINDS_IN_ALLOW",
                    "strict allow 에 민감 kind (" + String.join(", ", sensitiveAllowed) + ") 가 포함되어 있습니다. 의도된 허용인지 확인."));
        }
    }

    private void validatePlurals(List<ResourceRule> rules, String field, List<PolicyWarning> out) {
        if (rules == null) {
            return;
        }
        for (ResourceRule r : rules) {
            if (r.kind() == null) continue;
            String lower = r.kind().toLowerCase(Locale.ROOT);
            if (KNOWN_NON_PLURAL.contains(lower)) {
                out.add(new PolicyWarning(
                        Severity.MEDIUM,
                        "PLURAL_TYPO",
                        field + " 의 'kind: " + r.kind() + "' 는 singular 형태 — RESTMapper 정규화는 plural 입니다. "
                                + "agent 측에서 매칭 실패 가능. '" + lower + "s' 형태로 변경 권장 (정확한 plural 은 "
                                + "kubectl api-resources 로 확인)."));
            }
        }
    }

    public enum Severity {
        HIGH,
        MEDIUM,
        LOW,
        INFO
    }

    public record PolicyWarning(Severity severity, String code, String message) {}
}
