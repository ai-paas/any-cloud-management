package com.aipaas.anycloud.domain.agent.upgrade;

import com.aipaas.anycloud.domain.agent.ClusterAgentEntity;
import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentUpgradeStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fleet upgrade — IN_PROGRESS upgrade 의 진행 상태 추적.
 *
 * <p>30초 주기로 IN_PROGRESS row 들을 sweep:
 * <ul>
 *   <li>같은 cluster_name 의 어느 row 라도 {@code agent_version} 이 {@code upgrade_target_image}
 *       의 tag 와 일치 → SUCCEEDED 전환. 새 pod 가 boot + heartbeat 도착했다는 의미.</li>
 *   <li>{@code upgrade_started_at} 이 60분 이상 지났는데 신버전 못 본 경우 → FAILED. 운영자가
 *       원인 파악 후 재시도 또는 cluster PAUSED 처리.</li>
 * </ul>
 *
 * <p>{@link AgentUpgradeService} 가 trigger 만 책임지고 본 monitor 가 자동 detection — 두 책임 분리.
 * Multi-instance 환경에서 한 노드만 sweep 하도록 ShedLock 사용.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentUpgradeProgressMonitor {

    /** 60분 안에 신버전 detect 못 하면 FAILED. K8s rolling update + bootstrap 합쳐도 10분 안에 가능. */
    private static final Duration UPGRADE_TIMEOUT = Duration.ofMinutes(60);

    private final ClusterAgentRepository clusterAgentRepository;

    @Scheduled(
            fixedDelayString = "${anycloud.upgrade.monitor.interval-ms:30000}",
            initialDelayString = "${anycloud.upgrade.monitor.initial-delay-ms:15000}")
    @SchedulerLock(name = "agentUpgradeMonitor", lockAtMostFor = "PT2M", lockAtLeastFor = "PT15S")
    @Transactional
    public void sweep() {
        // DB index 활용 scoped finder (WHERE upgrade_status IN (...)) — agent 1000+ 환경에서도 heap 전송 최소화.
        List<ClusterAgentEntity> inFlight = clusterAgentRepository.findByUpgradeStatusIn(
                List.of(ClusterAgentUpgradeStatus.IN_PROGRESS, ClusterAgentUpgradeStatus.PENDING));
        if (inFlight.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (ClusterAgentEntity primary : inFlight) {
            String target = primary.getUpgradeTargetImage();
            String targetTag = extractTag(target);
            if (targetTag == null) {
                continue; // 잘못된 target image — 추후 wait 만.
            }
            // 같은 cluster 의 row 중 어느 하나라도 target version 으로 갱신됐으면 SUCCEEDED.
            List<ClusterAgentEntity> siblings = clusterAgentRepository.findByClusterName(primary.getClusterName());
            boolean someoneOnTarget = siblings.stream().anyMatch(s -> targetTag.equals(s.getAgentVersion()));
            if (someoneOnTarget) {
                primary.setUpgradeStatus(ClusterAgentUpgradeStatus.SUCCEEDED);
                primary.setUpgradeCompletedAt(now);
                primary.setUpgradeError(null);
                clusterAgentRepository.save(primary);
                log.info(
                        "upgrade SUCCEEDED cluster={} target={} duration={}",
                        primary.getClusterName(),
                        target,
                        primary.getUpgradeStartedAt() == null
                                ? "?"
                                : Duration.between(primary.getUpgradeStartedAt(), now));
                continue;
            }
            // Timeout check.
            LocalDateTime started = primary.getUpgradeStartedAt();
            if (started != null && Duration.between(started, now).compareTo(UPGRADE_TIMEOUT) > 0) {
                primary.setUpgradeStatus(ClusterAgentUpgradeStatus.FAILED);
                primary.setUpgradeCompletedAt(now);
                primary.setUpgradeError(
                        "Timeout — new agent_version not reported within " + UPGRADE_TIMEOUT.toMinutes() + " minutes");
                clusterAgentRepository.save(primary);
                log.warn(
                        "upgrade FAILED (timeout) cluster={} target={} source={}",
                        primary.getClusterName(),
                        target,
                        primary.getUpgradeSourceVersion());
            }
        }
    }

    /** "registry.local/cluster-agent:v1.2.3" → "v1.2.3". colon 없으면 null. */
    private static String extractTag(String image) {
        if (image == null || image.isBlank()) {
            return null;
        }
        int colonIdx = image.lastIndexOf(':');
        if (colonIdx <= 0 || colonIdx >= image.length() - 1) {
            return null;
        }
        return image.substring(colonIdx + 1);
    }
}
