package com.aipaas.anycloud.domain.agent.policy.internal;

import com.aipaas.anycloud.domain.agent.api.request.AgentPolicyUpdateRequest;
import com.aipaas.anycloud.domain.agent.policy.AgentPolicyMergeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.runtime.AgentPolicySnapshot;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AgentPolicyMergeServiceImpl implements AgentPolicyMergeService {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @Override
    public AgentPolicyUpdateRequest mergeWithCurrent(AgentPolicyUpdateRequest req, AgentPolicySnapshot current) {
        List<String> namespaces = req.allowedNamespaces();
        if (namespaces == null) {
            namespaces = new ArrayList<>(current.allowedNamespaces());
            if (current.allowAllNamespaces()) {
                namespaces.add("*");
            }
        }
        List<String> commands = req.allowedCommands();
        if (commands == null) {
            commands = current.allowedCommands();
        }
        List<String> charts = req.allowedCharts();
        if (charts == null) {
            charts = current.allowedCharts();
        }
        List<String> execNs = req.allowedExecNamespaces();
        if (execNs == null) {
            execNs = new ArrayList<>(current.allowedExecNamespaces());
            if (current.allowAllExecNamespaces()) {
                execNs.add("*");
            }
        }
        AgentPolicySnapshot.ResourcePolicy policy = req.resourcePolicy();
        if (policy == null) {
            policy = current.resourcePolicy();
        }
        return new AgentPolicyUpdateRequest(namespaces, commands, charts, execNs, policy, req.force());
    }

    @Override
    public AgentPolicyUpdateRequest applyMergePatch(JsonNode body, AgentPolicySnapshot current) throws Exception {

        List<String> namespaces = mergeStringList(body, "allowedNamespaces", buildNsListFromSnapshot(current));
        List<String> commands = mergeStringList(body, "allowedCommands", current.allowedCommands());
        List<String> charts = mergeStringList(body, "allowedCharts", current.allowedCharts());
        List<String> execNs = mergeStringList(body, "allowedExecNamespaces", buildExecNsListFromSnapshot(current));

        // resourcePolicy: has + isNull → null 으로 clear (RFC 7396 의미), absent → current 유지.
        AgentPolicySnapshot.ResourcePolicy resourcePolicy;
        if (!body.has("resourcePolicy")) {
            resourcePolicy = current.resourcePolicy();
        } else if (body.get("resourcePolicy").isNull()) {
            resourcePolicy = null;
        } else {
            resourcePolicy =
                    JSON_MAPPER.treeToValue(body.get("resourcePolicy"), AgentPolicySnapshot.ResourcePolicy.class);
        }

        // force flag — primitive boolean 이라 RFC 7396 의 null=clear 의미 적용 안 함.
        boolean force = body.has("force") && body.get("force").asBoolean(false);

        return new AgentPolicyUpdateRequest(namespaces, commands, charts, execNs, resourcePolicy, force);
    }

    /** RFC 7396 — has(field) 안 보이면 current, null 이면 empty list, 값이면 사용. */
    static List<String> mergeStringList(JsonNode body, String field, List<String> currentValue) {
        if (!body.has(field)) {
            return currentValue;
        }
        JsonNode node = body.get(field);
        if (node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(field + " must be an array or null");
        }
        List<String> out = new ArrayList<>(node.size());
        node.forEach(item -> out.add(item.asText()));
        return out;
    }

    /**
     * Snapshot 의 {@code allowedNamespaces} + wildcard flag 를 합쳐 단일 list 로. RFC 7396 merge 의
     * "current value" 계산에 사용 — wildcard 가 set 이면 list 에 {@code "*"} 추가.
     */
    static List<String> buildNsListFromSnapshot(AgentPolicySnapshot s) {
        List<String> out = new ArrayList<>(s.allowedNamespaces());
        if (s.allowAllNamespaces() && !out.contains("*")) out.add("*");
        return out;
    }

    /** {@link #buildNsListFromSnapshot} 의 exec-namespace 버전. */
    static List<String> buildExecNsListFromSnapshot(AgentPolicySnapshot s) {
        List<String> out = new ArrayList<>(s.allowedExecNamespaces());
        if (s.allowAllExecNamespaces() && !out.contains("*")) out.add("*");
        return out;
    }
}
