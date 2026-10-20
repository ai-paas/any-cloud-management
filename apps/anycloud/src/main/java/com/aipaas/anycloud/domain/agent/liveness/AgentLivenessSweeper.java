package com.aipaas.anycloud.domain.agent.liveness;

import com.aipaas.anycloud.domain.agent.ClusterAgentEntity;
import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import com.aipaas.anycloud.domain.events.ResourceChangedEvent;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * heartbeat 가 중단된 ACTIVE agent 를 DEGRADED 로 하향.
 *
 * <p>등록은 상태를 상향하나 하향 경로가 없어 cluster 삭제 후에도 행이 ACTIVE 로 잔존.
 * 업그레이드 대상과 카탈로그가 {@code status == ACTIVE} 로 필터링.
 *
 * <p>설정
 * <ul>
 *   <li>{@code anycloud.agent.liveness.enabled} (default true)</li>
 *   <li>{@code anycloud.agent.liveness.timeout} (default 5m). 무응답 지속 시 하향</li>
 *   <li>{@code anycloud.agent.liveness.cron} (default 매분)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLivenessSweeper {

    private final ClusterAgentRepository agentRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${anycloud.agent.liveness.enabled:true}")
    private boolean enabled;

    @Value("${anycloud.agent.liveness.timeout:5m}")
    private Duration timeout;

    @Scheduled(cron = "${anycloud.agent.liveness.cron:0 * * * * *}")
    @Transactional
    public void demoteSilentAgents() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<ClusterAgentEntity> active = agentRepository.findByStatus(ClusterAgentStatus.ACTIVE);

        int demoted = 0;
        for (ClusterAgentEntity agent : active) {
            if (!AgentLiveness.isStale(agent.getStatus(), agent.getLastSeenAt(), now, timeout)) {
                continue;
            }
            agent.setStatus(AgentLiveness.staleStatus());
            agentRepository.save(agent);
            eventPublisher.publishEvent(new ResourceChangedEvent("agent", agent.getClusterName()));
            demoted++;
        }
        if (demoted > 0) {
            log.info("하트비트가 끊긴 에이전트 {}건을 DEGRADED 로 내렸다 (기준 {})", demoted, timeout);
        }
    }
}
