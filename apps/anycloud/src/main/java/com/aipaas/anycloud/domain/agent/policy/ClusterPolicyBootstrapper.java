package com.aipaas.anycloud.domain.agent.policy;

import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.cluster.model.ClusterStatus;
import com.aipaas.anycloud.domain.helmrepo.internal.HelmRepoListSerializer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.runtime.AgentPolicySnapshot;
import io.aipaas.cluster.agent.runtime.KubeResourceService;
import io.aipaas.cluster.agent.runtime.KubeRoutingException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Cluster 가 PENDING_AGENT → ACTIVE 로 전환된 직후 agent ConfigMap 의
 * {@code helm_repositories} key 를 backend 의 helm_repo 테이블 직렬화 결과로 자동 갱신.
 *
 * <p>{@link com.aipaas.anycloud.domain.agent.bootstrap.AgentBootstrapServiceImpl} 의
 * {@code backfillClusterFromAgent} 에서 ACTIVE 전환 후 호출. 비동기 (@Async) — agent stream
 * registration 본 경로에 영향 없음. 실패는 swallow + warn — 사용자가 추후 admin endpoint 로 재시도.
 *
 * <p>전략: 기존 ConfigMap 의 4 list (allowedNamespaces/Commands/Charts/ExecNamespaces) + resource_policy
 * 는 그대로 유지하고 {@code helm_repositories} 만 새로 push. {@code applyAgentConfig} 가 부분 update
 * 미지원이라 다른 값도 동일 값으로 reset (no-op 효과).
 *
 * <p>주의: 이 호출 자체가 {@code APPLY_AGENT_CONFIG} RPC — agent 의 allowed_commands 에 그 명령이
 * 빠지면 PERMISSION_DENIED 로 swallow. 그 경우 사용자가validator 또는 kubectl patch 로 복구.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClusterPolicyBootstrapper {

    private final KubeResourceService kubeResourceService;
    private final HelmRepoListSerializer helmRepoListSerializer;
    private final ObjectMapper objectMapper;
    private final ClusterRepository clusterRepository;
    /**
     * RBAC starter 의 BindingTemplate catalog. cluster ACTIVE 시점에 binding apply.
     * ObjectProvider lazy — starter 가 부재한 컨텍스트 (test, worker) 에서도 robust.
     */
    private final org.springframework.beans.factory.ObjectProvider<
                    io.aipaas.cluster.agent.rbac.port.BindingTemplateCatalog>
            bindingTemplateCatalogProvider;

    private final org.springframework.beans.factory.ObjectProvider<io.aipaas.cluster.agent.rbac.port.BindingApplyClient>
            bindingApplyClientProvider;
    // per-cluster push 의 parallel 실행. KUBERNETES_EXECUTOR (core=4, max=8).
    // Lombok @RequiredArgsConstructor 가 field-level @Qualifier 를 constructor param 으로 propagate
    // 안 하는 한계 회피 — @Resource(name=) 가 field-injection 시 Spring 이 직접 처리.
    @jakarta.annotation.Resource(name = com.aipaas.anycloud.configuration.properties.AsyncConfig.KUBERNETES_EXECUTOR)
    private org.springframework.core.task.AsyncTaskExecutor broadcastExecutor;

    @Async
    public void pushOnActive(String clusterName) {
        try {
            AgentPolicySnapshot current = kubeResourceService.getAgentConfig(clusterName);
            String namespacesJson = toJsonArray(current.allowedNamespaces());
            String commandsJson = toJsonArray(current.allowedCommands());
            String chartsJson = toJsonArray(current.allowedCharts());
            String execNsJson = toJsonArray(current.allowedExecNamespaces());
            // resource_policy YAML — getAgentConfig 가 record 형태로 반환. backend 가 다시 yaml 직렬화
            // 하기 까다로워 본 호출에선 빈 문자열 (agent 측이 빈 = no-op 으로 treat). 사용자가 정책 세팅
            // 했다면 admin endpoint 로 별도 관리.
            String resourcePolicyYaml = "";
            String helmRepositoriesJson = helmRepoListSerializer.serializeAll();

            String rv = kubeResourceService.applyAgentConfig(
                    clusterName,
                    namespacesJson,
                    commandsJson,
                    chartsJson,
                    execNsJson,
                    resourcePolicyYaml,
                    helmRepositoriesJson);
            log.info("ClusterPolicyBootstrapper: helm_repositories pushed cluster={} rv={}", clusterName, rv);
        } catch (KubeRoutingException e) {
            log.warn("ClusterPolicyBootstrapper: agent routing failed cluster={}: {}", clusterName, e.getMessage());
        } catch (Exception e) {
            log.warn("ClusterPolicyBootstrapper: unexpected error cluster={}: {}", clusterName, e.toString());
        }
    }

    /**
     * backend 의 helm_repo CRUD 이후 모든 ACTIVE cluster 에 fresh
     * helm_repositories 를 broadcast. 각 cluster 호출은 동기 (per-cluster) 이지만 broadcast 자체는
     * @Async — caller (HelmRepoServiceImpl) 의 transaction 종료 후 background. 부분 실패는
     * eventual consistency 로 swallow (next push 시 retry).
     */
    /**
     * fix — caller transaction 의 commit 이후에 broadcast 시작. caller 의 @Async 직접
     * 호출은 race (commit 전 다른 thread DB 조회 → 새 row 누락). TransactionalEventListener 가
     * commit phase 까지 기다린 후 dispatch + @Async 가 별도 thread 로.
     */
    @org.springframework.transaction.event.TransactionalEventListener(
            phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    @Async
    public void onHelmRepoChanged(com.aipaas.anycloud.domain.helmrepo.model.HelmRepoChangedEvent event) {
        log.debug("onHelmRepoChanged: repo={} op={}", event.repoName(), event.operation());
        broadcastHelmRepoChange();
    }

    /**
     * (+ +) — 모든 ACTIVE cluster 에 fresh helm_repositories broadcast.
     *
     * <p><b> parallel</b>: per-cluster push 를 KubernetesExecutor 에 fan-out. 10+ cluster 환경
     * 에서 latency = max(per-cluster) 가 되어 sequential 누적 회피.
     *
     * <p><b> retry</b>: transient KubeRoutingException 발생 시 exponential backoff (100/300/900ms)
     * 로 최대 3회 retry. NoActiveSession (agent 미접속) 은 retry 무의미 — 즉시 skip + 다음 ACTIVE
     * 전환 시 회복.
     */
    @Async
    public void broadcastHelmRepoChange() {
        List<com.aipaas.anycloud.domain.cluster.ClusterEntity> active =
                clusterRepository.findAllByStatus(ClusterStatus.ACTIVE);
        if (active.isEmpty()) {
            log.debug("broadcastHelmRepoChange: no ACTIVE clusters — skip");
            return;
        }
        log.info("broadcastHelmRepoChange: pushing to {} ACTIVE clusters (parallel)", active.size());

        java.util.List<java.util.concurrent.CompletableFuture<Boolean>> futures =
                new java.util.ArrayList<>(active.size());
        for (var c : active) {
            final String clusterName = c.getId();
            futures.add(java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> pushWithRetry(clusterName), broadcastExecutor));
        }
        java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                .join();

        int ok = 0;
        int fail = 0;
        for (var f : futures) {
            try {
                if (Boolean.TRUE.equals(f.get())) ok++;
                else fail++;
            } catch (Exception e) {
                fail++;
            }
        }
        log.info("broadcastHelmRepoChange: done — ok={}, fail={}", ok, fail);
    }

    /** exponential backoff retry. NoActiveSession 은 즉시 skip (회복 path 별도). */
    private boolean pushWithRetry(String clusterName) {
        long[] delays = {100L, 300L, 900L};
        for (int attempt = 0; attempt <= delays.length; attempt++) {
            try {
                pushOnActiveSync(clusterName);
                return true;
            } catch (io.aipaas.cluster.agent.runtime.KubeRoutingException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("No active agent session") || msg.contains("AGENT_UNAVAILABLE")) {
                    log.warn(
                            "pushWithRetry: cluster={} agent unavailable — skip (will recover on next reconnect)",
                            clusterName);
                    return false;
                }
                if (attempt == delays.length) {
                    log.warn("pushWithRetry: cluster={} exhausted retries: {}", clusterName, msg);
                    return false;
                }
                log.debug(
                        "pushWithRetry: cluster={} attempt {} failed: {} — retrying in {}ms",
                        clusterName,
                        attempt + 1,
                        msg,
                        delays[attempt]);
                try {
                    Thread.sleep(delays[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } catch (Exception e) {
                log.warn("pushWithRetry: cluster={} unexpected error: {}", clusterName, e.toString());
                return false;
            }
        }
        return false;
    }

    /** {@link #pushOnActive} 의 sync 변형 — broadcast loop 안에서 호출. 외부에서는 async 호출 권장. */
    void pushOnActiveSync(String clusterName) {
        AgentPolicySnapshot current = kubeResourceService.getAgentConfig(clusterName);
        String namespacesJson = toJsonArray(current.allowedNamespaces());
        String commandsJson = toJsonArray(current.allowedCommands());
        String chartsJson = toJsonArray(current.allowedCharts());
        String execNsJson = toJsonArray(current.allowedExecNamespaces());
        String resourcePolicyYaml = "";
        String helmRepositoriesJson = helmRepoListSerializer.serializeAll();
        // oidc_bindings field 는 빈 array 로 전송 (agent ConfigMap 호환, oidcbinding entity
        // 폐기 후 starter path 만 사용). agent OidcBindingReconciler 가 빈 list → existing
        // ClusterRoleBinding cleanup 안 함 (starter 가 label aipaas.io/managed-by=anycloud 로 별도 관리).
        String oidcBindingsJson = "[]";
        String rv = kubeResourceService.applyAgentConfig(
                clusterName,
                namespacesJson,
                commandsJson,
                chartsJson,
                execNsJson,
                resourcePolicyYaml,
                helmRepositoriesJson,
                oidcBindingsJson);
        log.info("pushOnActiveSync: cluster={} rv={}", clusterName, rv);

        // cluster ACTIVE 직후 starter path 로 binding fan-out. BindingTemplateCatalog 가 빈 catalog
        // 면 noop. 운영자가 binding-templates.yaml 채우는 시점부터 자동 활성.
        applyStarterBindings(clusterName);
    }

    private void applyStarterBindings(String clusterName) {
        var catalog = bindingTemplateCatalogProvider.getIfAvailable();
        var client = bindingApplyClientProvider.getIfAvailable();
        if (catalog == null || client == null) return;

        // cluster.tags 전달. forClusters matchLabels 기반 cross-cluster 정책 활성.
        // tags 가 null/empty 면 forClusters: matchLabels: {} 인 template (모든 cluster) 만 매칭.
        var clusterLabels = lookupClusterLabels(clusterName);
        var templates = catalog.resolveFor(clusterLabels);
        if (templates.isEmpty()) return;

        for (var template : templates) {
            for (var group : template.oidcGroupSelector().matchExact()) {
                try {
                    client.apply(clusterName, template, group, "system:cluster-active-bootstrap");
                } catch (RuntimeException e) {
                    log.warn(
                            "starter binding apply failed cluster={} template={} group={}: {}",
                            clusterName,
                            template.id(),
                            group,
                            e.toString());
                }
            }
        }
    }

    private java.util.Map<String, String> lookupClusterLabels(String clusterName) {
        return clusterRepository
                .findById(clusterName)
                .map(com.aipaas.anycloud.domain.cluster.ClusterEntity::getTags)
                .filter(java.util.Objects::nonNull)
                .orElse(java.util.Map.of());
    }

    private String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.warn("toJsonArray failed (list={}): {}", list, e.getMessage());
            return "[]";
        }
    }
}
