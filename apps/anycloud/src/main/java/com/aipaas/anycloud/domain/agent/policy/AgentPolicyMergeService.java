package com.aipaas.anycloud.domain.agent.policy;

import com.aipaas.anycloud.domain.agent.api.request.AgentPolicyUpdateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import io.aipaas.cluster.agent.runtime.AgentPolicySnapshot;

/**
 * Agent policy PATCH (legacy + RFC 7396) 의 merge 책임.
 *
 * <p>두 가지 merge semantics 제공:
 * <ul>
 *   <li>{@link #mergeWithCurrent} — legacy PATCH (null = 현재 값 유지)</li>
 *   <li>{@link #applyMergePatch} — RFC 7396 (field 명시 + null = 비우기)</li>
 * </ul>
 */
public interface AgentPolicyMergeService {

    /**
     * Legacy PATCH 의 merge — null 필드를 현재 snapshot 으로 fill-in. force flag 는 request 의 것 유지.
     *
     * <p>{@code allowedNamespaces / allowedExecNamespaces} 는 snapshot 의 wildcard flag
     * ({@code allowAllNamespaces / allowAllExecNamespaces}) 를 list 에 {@code "*"} 항목으로 복원.
     */
    AgentPolicyUpdateRequest mergeWithCurrent(AgentPolicyUpdateRequest req, AgentPolicySnapshot current);

    /**
     * RFC 7396 의 핵심 merge 로직 — 각 필드의 명시/null 여부에 따라 분기:
     * <ul>
     *   <li>{@code body.has(name) == false} → current 값 그대로 (변경 없음)</li>
     *   <li>{@code body.get(name).isNull() == true} → 비우기 (empty list 또는 null)</li>
     *   <li>그 외 → body 값 사용</li>
     * </ul>
     *
     * @throws Exception JSON parse 실패 (controller 가 400 응답으로 매핑)
     */
    AgentPolicyUpdateRequest applyMergePatch(JsonNode body, AgentPolicySnapshot current) throws Exception;
}
