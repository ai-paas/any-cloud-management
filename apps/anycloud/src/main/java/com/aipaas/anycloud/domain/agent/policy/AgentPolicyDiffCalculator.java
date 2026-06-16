package com.aipaas.anycloud.domain.agent.policy;

import com.aipaas.anycloud.domain.agent.api.request.AgentPolicyUpdateRequest;
import com.aipaas.anycloud.domain.agent.policy.AgentPolicyValidator.PolicyWarning;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.aipaas.cluster.agent.runtime.AgentPolicySnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * Agent policy 의 diff 계산 + dry-run snapshot 생성 + agent param 직렬화 책임.
 *
 * <p>책임:
 * <ul>
 *   <li>{@link #computeDiff} — 두 snapshot 간 list-level diff + resource_policy 변경 여부</li>
 *   <li>{@link #buildDryRunSnapshot} — request → 가상 snapshot (validator 입력용)</li>
 *   <li>{@link #toJsonArray}, {@link #toYamlOrEmpty}, {@link #ruleListToMap} — agent param 직렬화</li>
 *   <li>{@link #highestSeverity} — warning list → 최고 severity name</li>
 * </ul>
 */
@Service
public class AgentPolicyDiffCalculator {

    /** YAML mapper for resource_policy 직렬화. dump 시 quote / indent 안정화. */
    private static final ObjectMapper YAML_MAPPER =
            new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));

    /** JSON mapper for agent param 직렬화 (4개 list → JSON array string). */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /**
     * 두 snapshot 간 list 단위 diff. 각 list 의 추가/제거 항목 + resource_policy 변경 여부.
     *
     * <p>{@code before} 가 null 이면 {@code missingBefore=true} 의 {@link PolicyDiff} 반환 — diff
     * 계산 불가 표시 (apply 자체는 성공 가능).
     */
    public PolicyDiff computeDiff(AgentPolicySnapshot before, AgentPolicySnapshot after) {
        if (before == null) {
            return new PolicyDiff(null, null, null, null, null, null, null, true);
        }
        return new PolicyDiff(
                listDiff(before.allowedNamespaces(), after.allowedNamespaces()),
                listDiff(before.allowedCommands(), after.allowedCommands()),
                listDiff(before.allowedCharts(), after.allowedCharts()),
                listDiff(before.allowedExecNamespaces(), after.allowedExecNamespaces()),
                before.allowAllNamespaces() != after.allowAllNamespaces(),
                before.allowAllExecNamespaces() != after.allowAllExecNamespaces(),
                !Objects.equals(before.resourcePolicy(), after.resourcePolicy()),
                false);
    }

    /** 두 list 간 added/removed 계산. null → 빈 set 으로 처리. */
    static FieldDiff listDiff(List<String> before, List<String> after) {
        Set<String> b = before == null ? Set.of() : new HashSet<>(before);
        Set<String> a = after == null ? Set.of() : new HashSet<>(after);
        Set<String> added = new TreeSet<>(a);
        added.removeAll(b);
        Set<String> removed = new TreeSet<>(b);
        removed.removeAll(a);
        return new FieldDiff(List.copyOf(added), List.copyOf(removed));
    }

    /**
     * Request → 가상 snapshot 구성 — validator 가 정책 적용 *전* 미리 검증할 수 있게.
     *
     * <p>{@code "*"} 가 있으면 {@code allowAllNamespaces / allowAllExecNamespaces} flag 도 true.
     */
    public AgentPolicySnapshot buildDryRunSnapshot(AgentPolicyUpdateRequest req) {
        List<String> nss = req.allowedNamespaces() == null ? List.of() : req.allowedNamespaces();
        boolean allowAllNs = nss.stream().anyMatch("*"::equals);
        List<String> execNs = req.allowedExecNamespaces() == null ? List.of() : req.allowedExecNamespaces();
        boolean allowAllExecNs = execNs.stream().anyMatch("*"::equals);
        return new AgentPolicySnapshot(
                nss,
                allowAllNs,
                req.allowedCommands() == null ? List.of() : req.allowedCommands(),
                req.allowedCharts() == null ? List.of() : req.allowedCharts(),
                execNs,
                allowAllExecNs,
                req.resourcePolicy(),
                null,
                null);
    }

    /** List → JSON array string. null → {@code "[]"}. agent ConfigMap apply param 용. */
    public String toJsonArray(List<String> items) {
        try {
            return JSON_MAPPER.writeValueAsString(items == null ? Collections.emptyList() : items);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize list to JSON", e);
        }
    }

    /**
     * {@link AgentPolicySnapshot.ResourcePolicy} → YAML string. mode 가 비어있으면 빈 string 반환
     * (legacy 동작 — 정책 비활성).
     */
    public String toYamlOrEmpty(AgentPolicySnapshot.ResourcePolicy policy) {
        if (policy == null || policy.mode() == null || policy.mode().isBlank()) {
            return "";
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", policy.mode());
        map.put("deny", ruleListToMap(policy.deny()));
        map.put("allow", ruleListToMap(policy.allow()));
        try {
            return YAML_MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize resource_policy to YAML", e);
        }
    }

    /** Resource rule list → Map list (YAML 직렬화용). blank kind/namespace 는 omit. */
    public List<Map<String, Object>> ruleListToMap(List<AgentPolicySnapshot.ResourceRule> rules) {
        if (rules == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(rules.size());
        for (AgentPolicySnapshot.ResourceRule r : rules) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (r.kind() != null && !r.kind().isBlank()) m.put("kind", r.kind());
            if (r.namespace() != null && !r.namespace().isBlank()) m.put("namespace", r.namespace());
            out.add(m);
        }
        return out;
    }

    /** Warning list → 최고 severity 이름 (HIGH > MEDIUM > LOW > INFO). 빈 list → {@code "NONE"}. */
    public String highestSeverity(List<PolicyWarning> warnings) {
        return warnings.stream()
                .map(PolicyWarning::severity)
                .min(Comparator.naturalOrder())
                .map(Enum::name)
                .orElse("NONE");
    }

    // =================== DTO records ===================

    /**
     * 한 list field 의 diff — added/removed 두 list.
     */
    public record FieldDiff(List<String> added, List<String> removed) {

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("added", added);
            m.put("removed", removed);
            return m;
        }
    }

    /**
     * 전체 policy diff. 4개 list field + resource_policy + allow-all flag 변경 여부.
     *
     * <p>{@code missingBefore=true} — before snapshot fetch 실패. response 에 "diff 불가" 표시.
     */
    public record PolicyDiff(
            FieldDiff namespaces,
            FieldDiff commands,
            FieldDiff charts,
            FieldDiff execNamespaces,
            Boolean allowAllNamespacesChanged,
            Boolean allowAllExecNamespacesChanged,
            Boolean resourcePolicyChanged,
            boolean missingBefore) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (missingBefore) {
                m.put("missingBefore", true);
                m.put("note", "이전 snapshot fetch 실패 — diff 계산 불가 (apply 자체는 성공)");
                return m;
            }
            m.put("allowedNamespaces", namespaces == null ? Map.of() : namespaces.toMap());
            m.put("allowedCommands", commands == null ? Map.of() : commands.toMap());
            m.put("allowedCharts", charts == null ? Map.of() : charts.toMap());
            m.put("allowedExecNamespaces", execNamespaces == null ? Map.of() : execNamespaces.toMap());
            if (Boolean.TRUE.equals(allowAllNamespacesChanged)) {
                m.put("allowAllNamespacesChanged", true);
            }
            if (Boolean.TRUE.equals(allowAllExecNamespacesChanged)) {
                m.put("allowAllExecNamespacesChanged", true);
            }
            if (Boolean.TRUE.equals(resourcePolicyChanged)) {
                m.put("resourcePolicyChanged", true);
            }
            return m;
        }

        public String toAuditSummary(String rv, boolean force) {
            if (missingBefore) {
                return "rv=" + rv + ",diff=unknown" + (force ? ",force=true" : "");
            }
            StringBuilder sb = new StringBuilder();
            appendDiff(sb, "ns", namespaces);
            appendDiff(sb, "cmds", commands);
            appendDiff(sb, "charts", charts);
            appendDiff(sb, "execNs", execNamespaces);
            if (Boolean.TRUE.equals(resourcePolicyChanged)) {
                if (sb.length() > 0) sb.append(",");
                sb.append("resourcePolicy=changed");
            }
            if (sb.length() == 0) {
                sb.append("no-changes");
            }
            if (!rv.isEmpty()) {
                sb.append(",rv=").append(rv);
            }
            if (force) {
                sb.append(",force=true");
            }
            return sb.toString();
        }

        private static void appendDiff(StringBuilder sb, String label, FieldDiff fd) {
            if (fd == null || fd.isEmpty()) return;
            if (sb.length() > 0) sb.append(",");
            sb.append(label)
                    .append("=+")
                    .append(fd.added().size())
                    .append("/-")
                    .append(fd.removed().size());
        }
    }
}
