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
 * 하트비트가 끊긴 ACTIVE 에이전트를 DEGRADED 로 내린다.
 *
 * <p>등록은 상태를 올리는데 내리는 길이 없어, 클러스터가 삭제된 뒤에도 행이 ACTIVE 로 남았다.
 * 화면만의 문제가 아니다 — 업그레이드 대상과 카탈로그가 {@code status == ACTIVE} 로 거른다.
 *
 * <p>설정
 * <ul>
 *   <li>{@code anycloud.agent.liveness.enabled} (default true)</li>
 *   <li>{@code anycloud.agent.liveness.timeout} (default 5m) — 이 시간 침묵하면 내린다</li>
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
