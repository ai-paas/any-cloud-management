package com.aipaas.anycloud.domain.cluster.internal;

import com.aipaas.anycloud.common.error.exception.ClusterNotFoundException;
import com.aipaas.anycloud.configuration.properties.AsyncConfig;
import com.aipaas.anycloud.domain.agent.ClusterAgentEntity;
import com.aipaas.anycloud.domain.agent.ClusterAgentRepository;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import com.aipaas.anycloud.domain.cluster.ClusterConnectivityService;
import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.cluster.model.ClusterStatus;
import io.aipaas.cluster.agent.runtime.AgentHealthService;
import io.aipaas.cluster.agent.runtime.ClusterHealth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ClusterConnectivityService} impl. agent heartbeat 판정 + 주기 sweep scheduler.
 *
 * <p>ClusterServiceImpl 에서 분리. 동일 로직이지만 단일 책임 (connectivity/status sync).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClusterConnectivityServiceImpl implements ClusterConnectivityService {

    private final ClusterRepository clusterRepository;
    private final ClusterAgentRepository clusterAgentRepository;
    private final AgentHealthService agentHealthService;

    @Override
    public boolean testClusterConnection(String clusterName) {
        log.info("Testing connection for cluster: {}", clusterName);

        // 존재하지 않는 cluster 는 "도달 불가" 가 아니라 호출 오류다.
        clusterRepository.findById(clusterName).orElseThrow(() -> new ClusterNotFoundException(clusterName));

        ClusterHealth health = agentHealthService.getHealth(clusterName);
        if (health.healthy()) {
            log.info(
                    "Connection test OK (source=AGENT, heartbeat={}s ago): cluster={}",
                    health.lastSeenSecondsAgo(),
                    clusterName);
            return true;
        }
        // agent 가 유일한 경로다. backend 는 cluster 자격증명이 없어 직접 ping 할 수단이 없다.
        log.info("Connection test failed (agent unhealthy): cluster={}, {}", clusterName, health.summary());
        return false;
    }

    @Override
    @Transactional
    public void updateClusterVersionAndStatus(ClusterEntity clusterEntity) {
        log.info("Updating version and status for cluster: {}", clusterEntity.getId());

        // Agent path 우선 — heartbeat 신선하면 K8s API 호출 없이 cluster_agent 테이블만으로 결정.
        ClusterHealth health = agentHealthService.getHealth(clusterEntity.getId());
        if (health.healthy()) {
            String version = lookupAgentK8sVersion(clusterEntity.getId());
            clusterEntity.transitionStatus(ClusterStatus.ACTIVE, "health.ok");
            clusterEntity.setVersion(version);
            clusterRepository.save(clusterEntity);
            log.info(
                    "Cluster {} updated (source=AGENT): status=ACTIVE, version={}, heartbeat={}s ago",
                    clusterEntity.getId(),
                    version,
                    health.lastSeenSecondsAgo());
            return;
        }

        // agent 가 유일한 경로다. heartbeat 가 없으면 버전도 상태도 알 수 없다.
        log.warn("Cluster {} is not accessible: {}", clusterEntity.getId(), health.summary());
        clusterEntity.transitionStatus(ClusterStatus.INACTIVE, "health.fail");
        clusterEntity.setVersion("UNKNOWN");
        clusterRepository.save(clusterEntity);
    }

    @Override
    @Async(AsyncConfig.KUBERNETES_EXECUTOR)
    public CompletableFuture<Void> updateClusterVersionAndStatusAsync(ClusterEntity clusterEntity) {
        log.info("Starting async version and status update for cluster: {}", clusterEntity.getId());
        try {
            updateClusterVersionAndStatus(clusterEntity);
            log.info("Async version and status update completed for cluster: {}", clusterEntity.getId());
        } catch (Exception e) {
            log.error(
                    "Async version and status update failed for cluster {}: {}",
                    clusterEntity.getId(),
                    e.getMessage(),
                    e);
        }
        return CompletableFuture.completedFuture(null);
    }

    /** Periodic sweep 시 한 chunk 당 fan-out 되는 cluster 수. ClusterCertExpiryMonitor 와 동일한 50 — 너무 크면 KUBERNETES_EXECUTOR pool (core 4 / max 8) 가 한 chunk 의 처리에 묶여 caller-runs backpressure 가 발생해 sweep 자체가 느려진다. */
    private static final int FAN_OUT_CHUNK_SIZE = 50;

    @Override
    public void updateAllClusterStatuses() {
        // updateClusterVersionAndStatusAsync (@Async KUBERNETES_EXECUTOR) 로 cluster-당 thread fan-out.
        // outer @Transactional 사용 금지 — child 가 자체 tx 보유 + outer tx 가 child 결과 보장 못 함
        // (best-effort sweep). outer tx 가 모든 child 끝까지 connection 점유하면 pool 고갈.
        //
        log.info("Starting periodic cluster status update (page-by-page fan-out)");

        int totalProcessed = 0;
        int page = 0;
        boolean hasNext = true;
        List<CompletableFuture<Void>> allFutures = new ArrayList<>();

        while (hasNext) {
            Page<ClusterEntity> chunk = clusterRepository.findAll(PageRequest.of(page, FAN_OUT_CHUNK_SIZE));
            if (chunk.isEmpty()) {
                break;
            }
            List<CompletableFuture<Void>> chunkFutures = new ArrayList<>(chunk.getNumberOfElements());
            for (ClusterEntity cluster : chunk.getContent()) {
                chunkFutures.add(updateClusterVersionAndStatusAsync(cluster));
            }
            // chunk 안의 모든 future 완료까지 대기 — pool 가 full 이면 caller-runs backpressure 로 자연
            // throttling. chunk 간 sleep 불요 — 모든 thread 가 가용해진 시점에 다음 chunk fetch.
            CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0]))
                    .join();
            allFutures.addAll(chunkFutures);
            totalProcessed += chunk.getNumberOfElements();
            page++;
            hasNext = chunk.hasNext();
        }

        if (totalProcessed == 0) {
            log.info("No clusters to update");
            return;
        }
        log.info(
                "Periodic cluster status update fan-out completed for {} clusters across {} pages",
                totalProcessed,
                page);
    }

    /**
     * cluster_agent 테이블에서 가장 최신 ACTIVE agent 의 k8sVersion 을 조회. 여러 agent 가 등록되어
     * 있으면 (HA) lastSeenAt 가 최신인 것 우선. 값이 없으면 "Version Unknown".
     */
    private String lookupAgentK8sVersion(String clusterName) {
        List<ClusterAgentEntity> agents =
                clusterAgentRepository.findByClusterNameAndStatus(clusterName, ClusterAgentStatus.ACTIVE);
        if (agents.isEmpty()) {
            return "Version Unknown";
        }
        return agents.stream()
                .max(Comparator.comparing(
                        ClusterAgentEntity::getLastSeenAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(ClusterAgentEntity::getK8sVersion)
                .filter(v -> v != null && !v.isBlank())
                .orElse("Version Unknown");
    }
}
