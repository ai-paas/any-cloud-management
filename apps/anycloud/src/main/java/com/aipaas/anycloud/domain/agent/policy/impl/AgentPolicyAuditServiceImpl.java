package com.aipaas.anycloud.domain.agent.policy.impl;

import com.aipaas.anycloud.configuration.properties.AsyncConfig;
import com.aipaas.anycloud.domain.agent.policy.AgentPolicyAuditService;
import com.aipaas.anycloud.domain.agent.policy.AgentPolicyValidator;
import com.aipaas.anycloud.domain.agent.policy.AgentPolicyValidator.PolicyWarning;
import com.aipaas.anycloud.domain.agent.policy.AgentPolicyValidator.Severity;
import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterService;
import com.aipaas.anycloud.domain.cluster.model.ClusterStatus;
import io.aipaas.cluster.agent.runtime.AgentPolicySnapshot;
import io.aipaas.cluster.agent.runtime.KubeResourceService;
import io.aipaas.cluster.agent.runtime.KubeRoutingException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * {@link AgentPolicyAuditService} impl. parallel CompletableFuture + per-cluster timeout +
 * severity 집계. {@link AsyncConfig#KUBERNETES_EXECUTOR} 위에서 fan-out.
 */
@Slf4j
@Service
public class AgentPolicyAuditServiceImpl implements AgentPolicyAuditService {

    /** Fleet audit 의 cluster-별 timeout — 한 cluster 가 끝나기 늦어도 전체 audit 가 무너지지 않게. */
    private static final long PER_CLUSTER_TIMEOUT_SECONDS = 10L;

    private final KubeResourceService kubeResourceService;
    private final AgentPolicyValidator validator;
    private final ClusterService clusterService;
    private final Executor kubernetesExecutor;

    public AgentPolicyAuditServiceImpl(
            KubeResourceService kubeResourceService,
            AgentPolicyValidator validator,
            ClusterService clusterService,
            @Qualifier(AsyncConfig.KUBERNETES_EXECUTOR) Executor kubernetesExecutor) {
        this.kubeResourceService = kubeResourceService;
        this.validator = validator;
        this.clusterService = clusterService;
        this.kubernetesExecutor = kubernetesExecutor;
    }

    @Override
    public Map<String, Object> runFleetAudit() {
        // totalClusters / scannedClusters / unreachableClusters 응답 의미 보존을 위해 전체 fetch 후
        // auditOneCluster 에서 non-ACTIVE 를 UNREACHABLE 로 마킹. ACTIVE-only fetch 최적화는 별도
        // PR 에서 totalClusters semantics 마이그레이션과 함께 진행.
        List<ClusterEntity> clusters = clusterService.getClusterEntities();

        long startMs = System.currentTimeMillis();
        // kubernetesExecutor 가 pool size 로 동시성 상한 (default 8). caller-runs 정책이라 queue 초과
        // 시 caller 가 직접 실행 — runtime backpressure.
        List<CompletableFuture<Map<String, Object>>> futures = clusters.stream()
                .map(c -> CompletableFuture.supplyAsync(() -> auditOneCluster(c), kubernetesExecutor)
                        .completeOnTimeout(timeoutEntry(c), PER_CLUSTER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .exceptionally(t -> errorEntry(c, t)))
                .toList();

        List<Map<String, Object>> clusterEntries =
                futures.stream().map(CompletableFuture::join).collect(Collectors.toCollection(ArrayList::new));

        long durationMs = System.currentTimeMillis() - startMs;

        // 집계
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        bySeverity.put("HIGH", 0);
        bySeverity.put("MEDIUM", 0);
        bySeverity.put("LOW", 0);
        bySeverity.put("INFO", 0);
        bySeverity.put("NONE", 0);
        bySeverity.put("UNREACHABLE", 0);
        int totalWarnings = 0;
        for (Map<String, Object> entry : clusterEntries) {
            String sev = (String) entry.get("highestSeverity");
            bySeverity.merge(sev, 1, Integer::sum);
            Object wc = entry.get("warningCount");
            if (wc instanceof Integer i) {
                totalWarnings += i;
            }
        }

        // HIGH 부터 정렬 (운영자 시야에 문제 cluster 우선)
        clusterEntries.sort(Comparator.comparingInt(e -> severityRank((String) e.get("highestSeverity"))));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalClusters", clusters.size());
        body.put("scannedClusters", clusters.size() - bySeverity.get("UNREACHABLE"));
        body.put("unreachableClusters", bySeverity.get("UNREACHABLE"));
        body.put("totalWarnings", totalWarnings);
        body.put("bySeverity", bySeverity);
        body.put("durationMs", durationMs);
        body.put("clusters", clusterEntries);

        log.info(
                "Fleet audit: total={}, byHighSeverity={}, byUnreachable={}, durationMs={}",
                clusters.size(),
                bySeverity.get("HIGH"),
                bySeverity.get("UNREACHABLE"),
                durationMs);

        return body;
    }

    /** Per-cluster audit — kubernetesExecutor 위에서 실행. exception 은 외부에서 처리. */
    private Map<String, Object> auditOneCluster(ClusterEntity cluster) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("clusterName", cluster.getId());
        entry.put(
                "clusterStatus",
                cluster.getStatus() == null ? "UNKNOWN" : cluster.getStatus().name());

        if (cluster.getStatus() != ClusterStatus.ACTIVE) {
            entry.put("highestSeverity", "UNREACHABLE");
            entry.put("note", "cluster not ACTIVE — skipped");
            return entry;
        }
        try {
            AgentPolicySnapshot snapshot = kubeResourceService.getAgentConfig(cluster.getId());
            List<PolicyWarning> warnings = validator.validate(snapshot);
            String highest = highestSeverity(warnings);
            entry.put("highestSeverity", highest);
            entry.put("warningCount", warnings.size());
            entry.put(
                    "topCodes",
                    warnings.stream()
                            .filter(w -> w.severity() == Severity.HIGH || w.severity() == Severity.MEDIUM)
                            .map(PolicyWarning::code)
                            .distinct()
                            .limit(3)
                            .toList());
            entry.put("lastReloadAt", snapshot.lastReloadAt());
            entry.put("configMapResourceVersion", snapshot.configMapResourceVersion());
            return entry;
        } catch (KubeRoutingException e) {
            entry.put("highestSeverity", "UNREACHABLE");
            entry.put("error", e.getMessage());
            return entry;
        }
    }

    private static Map<String, Object> timeoutEntry(ClusterEntity cluster) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("clusterName", cluster.getId());
        entry.put(
                "clusterStatus",
                cluster.getStatus() == null ? "UNKNOWN" : cluster.getStatus().name());
        entry.put("highestSeverity", "UNREACHABLE");
        entry.put("error", "audit timeout (>" + PER_CLUSTER_TIMEOUT_SECONDS + "s)");
        return entry;
    }

    private static Map<String, Object> errorEntry(ClusterEntity cluster, Throwable t) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("clusterName", cluster.getId());
        entry.put(
                "clusterStatus",
                cluster.getStatus() == null ? "UNKNOWN" : cluster.getStatus().name());
        entry.put("highestSeverity", "UNREACHABLE");
        entry.put("error", t instanceof TimeoutException ? "audit timeout" : t.getMessage());
        return entry;
    }

    private static String highestSeverity(List<PolicyWarning> warnings) {
        return warnings.stream()
                .map(PolicyWarning::severity)
                .min(Comparator.naturalOrder())
                .map(Enum::name)
                .orElse("NONE");
    }

    /** UI 정렬용 — HIGH 가 위로. */
    private static int severityRank(String severity) {
        return switch (severity) {
            case "HIGH" -> 0;
            case "UNREACHABLE" -> 1;
            case "MEDIUM" -> 2;
            case "LOW" -> 3;
            case "INFO" -> 4;
            case "NONE" -> 5;
            default -> 99;
        };
    }
}
