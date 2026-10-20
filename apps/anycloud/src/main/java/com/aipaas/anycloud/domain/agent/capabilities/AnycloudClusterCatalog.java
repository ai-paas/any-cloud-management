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
 *   <li>cluster_agent.status == ACTIVE: agent 가 실제로 backend 와 stream 연결된 상태</li>
 *   <li>HA 시 같은 cluster 에 ACTIVE 행이 여러 개여도 distinct cluster_name 으로 dedup</li>
 * </ul>
 *
 * <p>ACTIVE 가 아닌 cluster 는 agent stream 이 없어 PromQL 호출이 NO_ACTIVE_AGENT 로 실패합니다.
 * 카탈로그에서 미리 걸러내면 여러 cluster 를 동시에 조회할 때 실패 응답이 섞이지 않습니다.
 *
 * <p>이 bean 이 있어야 {@code ClusterObservabilityAutoConfiguration} 의
 * {@code @ConditionalOnBean(ClusterCatalog.class)} 가 충족돼 query, install, dashboard service 가
 * 등록됩니다.
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
