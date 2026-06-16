package com.aipaas.anycloud.domain.cluster.kubeconfig.internal;

import com.aipaas.anycloud.domain.agent.AgentProperties;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class KubeconfigLifecycleServiceImpl implements KubeconfigLifecycleService {

    private final ClusterRepository clusterRepository;
    /** @Value 분산 inject → AgentProperties 단일 진입점. */
    private final AgentProperties agentProperties;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    /**
     * ClusterEntity 의 serverCa / clientCa / clientKey / clientToken 컬럼이 모두
     * DROP 됐으므로 cleanup 대상 자체가 없음. agent ACTIVE 전환은 backend bootstrap service 가
     * 처리. 본 메서드는 항상 false (no-op).
     */
    public boolean maybeCleanupOnActive(String clusterName) {
        log.debug("Kubeconfig cleanup no-op: admin credentials 컬럼 제거됨 cluster_name={}", clusterName);
        return false;
    }
}
