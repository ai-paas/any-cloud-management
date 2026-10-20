package com.aipaas.anycloud.domain.agent.policy;

import com.aipaas.anycloud.domain.agent.api.request.AgentPolicyUpdateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import io.aipaas.cluster.agent.runtime.AgentPolicySnapshot;

/**
 * agent policy PATCH 병합. null 해석이 메서드별로 다르므로 호출 대상 확인 필요.
 * {@link #mergeWithCurrent} 는 null 을 값 유지로, {@link #applyMergePatch} 는 값 제거로 해석.
 */
public interface AgentPolicyMergeService {

    /** null 필드를 현재 snapshot 값으로 채움. force 플래그는 요청 값 유지. */
    AgentPolicyUpdateRequest mergeWithCurrent(AgentPolicyUpdateRequest req, AgentPolicySnapshot current);

    /**
     * RFC 7396 merge patch. 키 부재 시 현재 값 유지, 키 존재이고 값이 null 이면 제거, 그 외에는
     * 전달 값으로 치환. 키 부재와 null 은 서로 다른 의미.
     *
     * @throws Exception JSON 파싱 실패. controller 가 400 으로 매핑
     */
    AgentPolicyUpdateRequest applyMergePatch(JsonNode body, AgentPolicySnapshot current) throws Exception;
}
