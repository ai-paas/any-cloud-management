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
 * <p>모니터링 대상 cluster 의 정의:
 * <ul>
 *   <li>cluster_agent.status == ACTIVE. agent 가 backend 와 stream 연결된 상태</li>
 *   <li>HA 로 동일 cluster 에 ACTIVE 행이 여럿이어도 distinct cluster_name 으로 dedup</li>
 * </ul>
 *
 * <p>ACTIVE 가 아닌 cluster 는 agent stream 부재로 PromQL 호출이 NO_ACTIVE_AGENT 실패.
 * 카탈로그에서 사전 필터링하면 다중 cluster 동시 조회 시 실패 응답 혼입 방지.
 *
 * <p>이 bean 이 있어야 {@code ClusterObservabilityAutoConfiguration} 의
 * {@code @ConditionalOnBean(ClusterCatalog.class)} 가 충족돼 query, install, dashboard service
 * 등록.
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
