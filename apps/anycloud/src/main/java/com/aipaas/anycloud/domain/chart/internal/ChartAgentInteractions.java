package com.aipaas.anycloud.domain.chart.internal;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import io.aipaas.cluster.agent.runtime.AgentHealthService;
import io.aipaas.cluster.agent.runtime.ClusterHealth;
import io.aipaas.cluster.agent.runtime.HelmReleaseService;
import io.aipaas.cluster.agent.runtime.HelmRoutingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Chart 모듈의 agent 상호작용 공통 helper.
 *
 * <p>3 helper 제공:
 * <ul>
 *   <li>{@link #requireHelmAgent} — agent isActiveFor=false 면 즉시 503 throw</li>
 *   <li>{@link #wrapHelmRouting} — agent action 실행 + {@link HelmRoutingException} 분류</li>
 *   <li>{@link #helmCall} — requireHelmAgent + wrapHelmRouting 1줄 단축 (가장 흔한 case)</li>
 * </ul>
 *
 * <p>ChartServiceImpl 내부 helper pattern 을 그대로 유지. caller 측 API 변경 0.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChartAgentInteractions {

    /** Day-2 Helm ops 를 agent gRPC 로 routing. */
    private final HelmReleaseService helmReleaseService;
    /** 503 시 진단 hint (agent status / lastSeen / streamActive) 첨부. */
    private final AgentHealthService agentHealthService;

    /**
     * agent isActiveFor=false 면 즉시 503 throw — agent stream 없는 cluster 에 helm op 시도 차단.
     */
    public void requireHelmAgent(String clusterName, String operationDesc) {
        if (!helmReleaseService.isActiveFor(clusterName)) {
            log.warn("{} requires active agent (cluster={})", operationDesc, clusterName);
            throw agentUnavailable(clusterName, operationDesc);
        }
    }

    /**
     * agent action 을 try/catch 로 감싸 {@link HelmRoutingException} 을 운영자용 에러로 분류.
     *
     * <p>Caller 가 pre-work 가 필요한 케이스 (install / upgrade — repo lookup + tarball pre-fetch)
     * 에서는 {@link #requireHelmAgent} 를 일찍 호출하고, 본 wrap 만 별도 사용.
     */
    public <R> R wrapHelmRouting(String clusterName, String operationDesc, String context, HelmAction<R> action) {
        try {
            return action.execute();
        } catch (HelmRoutingException e) {
            log.error("Agent {} failed (cluster={}): {}", operationDesc, clusterName, e.getMessage());
            throw HelmExceptionMapper.toClassifiedException(operationDesc, context, e);
        }
    }

    /** {@code requireHelmAgent + wrapHelmRouting} 단축 — 가장 흔한 case (pre-work 없음). */
    public <R> R helmCall(String clusterName, String operationDesc, String context, HelmAction<R> action) {
        requireHelmAgent(clusterName, operationDesc + " " + context);
        return wrapHelmRouting(clusterName, operationDesc, context, action);
    }

    /** Helm agent action functional interface (checked exception 허용). */
    @FunctionalInterface
    public interface HelmAction<R> {
        R execute() throws HelmRoutingException;
    }

    /**
     * 503 AGENT_UNAVAILABLE 의 진단 강화 — agent status / stream / lastSeen 정보를 error 에 첨부해
     * 운영자가 즉시 다음 action 식별 가능.
     */
    private CustomException agentUnavailable(String clusterName, String operation) {
        ClusterHealth health = agentHealthService.getHealth(clusterName);
        String hint;
        if (!health.hasAgent()) {
            hint = "agent 미등록 — POST /v1/clusters/" + clusterName + "/agent-registration "
                    + "으로 token 발급 + helm install 권고";
        } else if (!health.streamActive()) {
            Long last = health.lastSeenSecondsAgo();
            hint = "agent 등록 (status=" + health.agentStatus() + ") 됐지만 stream 끊김"
                    + (last == null ? "" : " (lastSeen=" + last + "s ago)")
                    + " — agent pod 실행 확인 + restart 권고";
        } else {
            hint = "agent stream 활성으로 보이나 routing 실패 — backend multi-replica + sticky "
                    + "session 미설정 가능성 (별도 sprint 필요)";
        }
        log.warn("AGENT_UNAVAILABLE on {}: operation='{}', hint='{}'", clusterName, operation, hint);
        return new CustomException(
                ErrorCode.AGENT_UNAVAILABLE,
                "cluster",
                clusterName,
                "Cannot " + operation + ". " + hint + " | health: " + health.summary());
    }
}
