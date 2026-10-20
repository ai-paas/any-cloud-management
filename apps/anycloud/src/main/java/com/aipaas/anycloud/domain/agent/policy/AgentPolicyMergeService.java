package com.aipaas.anycloud.domain.agent.policy;

import com.aipaas.anycloud.domain.agent.api.request.AgentPolicyUpdateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import io.aipaas.cluster.agent.runtime.AgentPolicySnapshot;

/**
 * agent policy PATCH 의 병합입니다. null 의 뜻이 둘로 갈리니 어느 쪽을 부르는지 확인하세요.
 * {@link #mergeWithCurrent} 는 null 을 "그대로 두기" 로, {@link #applyMergePatch} 는 "비우기" 로
 * 읽습니다.
 */
public interface AgentPolicyMergeService {

    /** null 필드를 현재 snapshot 값으로 채웁니다. force 플래그는 요청 값을 그대로 둡니다. */
    AgentPolicyUpdateRequest mergeWithCurrent(AgentPolicyUpdateRequest req, AgentPolicySnapshot current);

    /**
     * RFC 7396 merge patch 입니다. 키가 없으면 현재 값을 두고, 키가 있고 값이 null 이면 비우고,
     * 그 밖에는 준 값으로 바꿉니다. "키 없음" 과 "null" 이 서로 다른 뜻이라는 점이 핵심입니다.
     *
     * @throws Exception JSON 파싱 실패. controller 가 400 으로 매핑합니다.
     */
    AgentPolicyUpdateRequest applyMergePatch(JsonNode body, AgentPolicySnapshot current) throws Exception;
}
