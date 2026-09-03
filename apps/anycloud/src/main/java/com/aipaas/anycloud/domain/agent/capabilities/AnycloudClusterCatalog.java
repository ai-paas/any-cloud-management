package com.aipaas.anycloud.domain.agent.capabilities;

import com.aipaas.anycloud.domain.agent.ClusterAgentEntity;
import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import io.aipaas.cluster.agent.observability.port.ClusterCatalog;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * cluster-observability starter 의 {@link ClusterCatalog} 를 anycloud 의 cluster_agent 테이블 위에 구현.
 *
 * <p>"모니터링 가능한 cluster" 의 정의:
 * <ul>
 *   <li>cluster_agent.status == ACTIVE — agent 가 실제로 backend 와 stream 연결된 상태</li>
 *   <li>HA 시 같은 cluster 에 ACTIVE 행이 여러 개여도 distinct cluster_name 으로 dedup</li>
 * </ul>
 *
 * <p>ACTIVE 가 아닌 cluster 는 어차피 agent stream 이 없어 PromQL 호출이 NO_ACTIVE_AGENT 로 실패. 미리
 * 카탈로그에서 걸러주면 multi-cluster fan-out 시 잡음 감소.
 *
 * <p>본 adapter 가 inject 됨으로써 {@code ClusterObservabilityAutoConfiguration} 의 {@code
 * @ConditionalOnBean(ClusterCatalog.class)} 조건이 충족 → query/install/dashboard service 들이
 * 자동 wire.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnycloudClusterCatalog implements ClusterCatalog {

    private final ClusterAgentRepository agentRepository;

    @Override
    @Transactional(readOnly = true)
    public List<String> listClusterNames() {
        return agentRepository.findByStatus(ClusterAgentStatus.ACTIVE).stream()
                .map(ClusterAgentEntity::getClusterName)
                .distinct()
                .toList();
    }
}
