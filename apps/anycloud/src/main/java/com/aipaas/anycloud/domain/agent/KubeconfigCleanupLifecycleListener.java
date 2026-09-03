package com.aipaas.anycloud.domain.agent;

import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigLifecycleService;
import io.aipaas.cluster.agent.core.AgentIdentity;
import io.aipaas.cluster.agent.core.AgentLifecycleListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Agent 가 ACTIVE 로 전환된 시점에 ClusterEntity 의 kubeconfig 필드를 cleanup 하는 anycloud-specific
 * 훅.
 *
 * <p>Starter 의 {@link AgentLifecycleListener#onStreamConnected} 가 첫 ACTIVE 전환마다 호출됨. Phase
 * 8c-1 의 kubeconfig 평문 보관 cleanup 옵션이 본 listener 로 wire 됨.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KubeconfigCleanupLifecycleListener implements AgentLifecycleListener {

    private final KubeconfigLifecycleService kubeconfigLifecycleService;

    @Override
    public void onStreamConnected(AgentIdentity agent) {
        try {
            kubeconfigLifecycleService.maybeCleanupOnActive(agent.clusterName());
        } catch (Exception e) {
            log.warn("Kubeconfig cleanup on agent ACTIVE failed cluster={}: {}", agent.clusterName(), e.toString());
        }
    }
}
