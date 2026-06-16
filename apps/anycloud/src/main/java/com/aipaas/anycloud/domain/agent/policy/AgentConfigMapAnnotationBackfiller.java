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
 * Backend startup 시 모든 ACTIVE cluster 의 ConfigMap 에 {@code helm.sh/resource-policy: keep}
 * annotation 누락 검출 + 자동 추가. 운영자 manual 명령 불요.
 *
 * <p>실행 패턴:
 * <ul>
 *   <li>{@link ApplicationReadyEvent} 수신 시 1회 실행 (startup 직후, Spring context 안정화 후)</li>
 *   <li>{@link AsyncConfig#KUBERNETES_EXECUTOR} 에서 비동기 — 다른 startup task 차단 안 함</li>
 *   <li>각 cluster 의 호출 결과 logging — 운영자가 startup 로그에서 backfill 상태 확인 가능</li>
 *   <li>한 cluster 실패해도 나머지 진행 (best-effort)</li>
 *   <li>이미 annotation 있는 ConfigMap 은 agent 측이 no-op — 멱등 보장</li>
 * </ul>
 *
 * <p><b>중요</b>: agent 가 {@code ENSURE_AGENT_CONFIG_ANNOTATIONS} command 지원하지 않는 (구) 이미지면
 * PERMISSION_DENIED 또는 UNKNOWN_COMMAND. 그 경우 운영자가 manual runbook 의 Option A (kubectl
 * annotate) 사용. logging 으로 식별 가능.
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
     * Hibernate 가 ready 상태 — DB / cluster registry 안전하게 query 가능.
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
