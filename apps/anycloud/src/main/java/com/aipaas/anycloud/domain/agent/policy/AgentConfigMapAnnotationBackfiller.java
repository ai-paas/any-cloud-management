package com.aipaas.anycloud.domain.agent.policy;

import com.aipaas.anycloud.configuration.properties.AsyncConfig;
import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterService;
import com.aipaas.anycloud.domain.cluster.model.ClusterStatus;
import io.aipaas.cluster.agent.runtime.KubeResourceService;
import io.aipaas.cluster.agent.runtime.KubeResourceService.EnsureAnnotationResult;
import io.aipaas.cluster.agent.runtime.KubeRoutingException;
import java.util.List;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 부팅 시 ACTIVE cluster 의 ConfigMap 에 {@code helm.sh/resource-policy: keep} 존재 여부를 확인
 * 하고 부재 시 추가. 이 annotation 이 없으면 helm upgrade 가 운영자 변경 정책을 덮어씀.
 *
 * <p>{@link ApplicationReadyEvent} 에서 1회, {@link AsyncConfig#KUBERNETES_EXECUTOR} 로 실행.
 * cluster 하나가 실패해도 나머지 계속 처리. 이미 존재하면 agent 측에서 no-op.
 *
 * <p>{@code ENSURE_AGENT_CONFIG_ANNOTATIONS} 미지원 구버전 agent image 는 PERMISSION_DENIED 또는
 * UNKNOWN_COMMAND 응답. 로그 확인 후 {@code kubectl annotate} 로 직접 추가.
 */
@Slf4j
@Component
public class AgentConfigMapAnnotationBackfiller {

    private final ClusterService clusterService;
    private final KubeResourceService kubeResourceService;
    private final Executor executor;

    public AgentConfigMapAnnotationBackfiller(
            ClusterService clusterService,
            KubeResourceService kubeResourceService,
            @Qualifier(AsyncConfig.KUBERNETES_EXECUTOR) Executor executor) {
        this.clusterService = clusterService;
        this.kubeResourceService = kubeResourceService;
        this.executor = executor;
    }

    /**
     * Startup 직후 trigger. {@code ApplicationReadyEvent} 시점은 모든 bean 초기화 완료 + Tomcat /
     * Hibernate 가 ready 상태: DB / cluster registry 안전하게 query 가능.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        executor.execute(this::backfillAll);
    }

    private void backfillAll() {
        List<ClusterEntity> clusters;
        try {
            clusters = clusterService.getClusterEntities();
        } catch (Exception e) {
            log.error("Backfill skipped — cluster enumeration failed: {}", e.getMessage());
            return;
        }

        int active = 0;
        int patched = 0;
        int alreadyPresent = 0;
        int failed = 0;
        int skipped = 0;
        long started = System.currentTimeMillis();

        for (ClusterEntity cluster : clusters) {
            if (cluster.getStatus() != ClusterStatus.ACTIVE) {
                skipped++;
                continue;
            }
            active++;
            try {
                EnsureAnnotationResult result = kubeResourceService.ensureAgentConfigAnnotations(cluster.getId());
                if (result.alreadyPresent()) {
                    alreadyPresent++;
                    log.debug("Backfill: {} already annotated (rv={})", cluster.getId(), result.resourceVersion());
                } else {
                    patched++;
                    log.info("Backfill: {} annotated (rv={})", cluster.getId(), result.resourceVersion());
                }
            } catch (KubeRoutingException e) {
                failed++;
                log.warn("Backfill failed for cluster {}: {}", cluster.getId(), e.getMessage());
            } catch (Exception e) {
                failed++;
                log.error("Unexpected error in backfill for cluster {}: {}", cluster.getId(), e.getMessage(), e);
            }
        }

        long durationMs = System.currentTimeMillis() - started;
        log.info(
                "ConfigMap annotation backfill done: total={}, ACTIVE={}, patched={}, "
                        + "alreadyPresent={}, failed={}, skipped={}, durationMs={}",
                clusters.size(),
                active,
                patched,
                alreadyPresent,
                failed,
                skipped,
                durationMs);

        if (failed > 0) {
            log.warn(
                    "Backfill: {} cluster(s) failed. 운영자가 manual migration 절차 (docs/runbooks/"
                            + "cluster-agent-configmap-migration.md Option A) 실행 권장.",
                    failed);
        }
    }
}
