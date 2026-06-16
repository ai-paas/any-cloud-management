package com.aipaas.anycloud.domain.cluster;

import com.aipaas.anycloud.configuration.persistence.KubernetesClientFactory;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h2>Bootstrap-only fabric8 KubernetesClient cache.</h2>
 *
 * <p> 이후 day-2 ops 는 cluster agent path 가 담당하고, 본 cache 는 <b>오직 bootstrap
 * 상황</b>에서만 사용된다 — 즉 agent 가 아직 ACTIVE 가 아니거나 처음 등록되는 cluster 에서의 K8s
 * 통신.
 *
 * <p>역사적으로 이 클래스는 {@code KubernetesClientCache} 였고 day-2 read/write 의 hot path 였다.
 * 작업 (HelmReleaseScanner 삭제, KubeServiceImpl 의 agent-only 전환 등) 으로 day-2 호출이
 * 모두 agent gRPC stream 으로 이동했고, 본 cache 의 caller 는 다음 3 종류 bootstrap 경로만 남았다:
 *
 * <ul>
 *   <li>{@code ClusterServiceImpl.testClusterConnection} — agent 미설치/inactive 시 fabric8 ping</li>
 *   <li>{@code ClusterServiceImpl.updateClusterVersionAndStatus} — agent 미보고 시 K8s version 직접 조회</li>
 *   <li>{@code VmClusterRegistrationServiceImpl.refreshClusterStatus} — VM 등록 직후 K8s 도달성 확인</li>
 *   <li>{@code ChartServiceImpl.deployChartFromFile} — legacy multipart deploy 의 pre-check</li>
 * </ul>
 *
 * <h3>정책</h3>
 * <ul>
 *   <li>캐시 대상은 *KubernetesClient 객체* 뿐 — K8s API 응답은 절대 캐싱하지 않는다.</li>
 *   <li>{@code expireAfterAccess(10m)} — 10분간 미사용 시 폐기 (close 자동 호출)</li>
 *   <li>{@code maximumSize(50)} — LRU. bootstrap-only 라 50 클러스터 cap 으로 충분.</li>
 *   <li>{@link ClusterEntity} update/delete 시 호출부에서 {@link #invalidate(String)} 명시 호출.</li>
 *   <li>{@link #execute(ClusterEntity, Function)} 사용 시 401/403 → 클라이언트 invalidate + 1회 자동 재시도.</li>
 * </ul>
 *
 * <h3>API surface</h3>
 * 외부 사용 표면을 최소화 — bootstrap 경로의 짧은 호출만 가능하도록 좁혔다:
 * <ul>
 *   <li>{@link #execute(ClusterEntity, Function)} — 결과값 있는 호출</li>
 *   <li>{@link #executeVoid(ClusterEntity, Consumer)} — 결과값 없는 호출</li>
 *   <li>{@link #invalidate(String)} — cluster delete/update 시</li>
 * </ul>
 * day-2 가 fabric8 client 를 직접 받아가던 {@code getClient()} 와 진단용 {@code asMap()} 은 제거됐다.
 *
 * @since Phase 3 — KubernetesClientCache 에서 이름 + 책임 narrow.
 */
@Slf4j
@Component
public class AgentBootstrapKubeClient {

    private static final Duration EXPIRE_AFTER_ACCESS = Duration.ofMinutes(10);
    private static final long MAX_SIZE = 50L;

    private Cache<String, KubernetesClient> cache;

    @PostConstruct
    void init() {
        this.cache = Caffeine.newBuilder()
                .expireAfterAccess(EXPIRE_AFTER_ACCESS)
                .maximumSize(MAX_SIZE)
                .removalListener((String key, KubernetesClient client, RemovalCause cause) -> {
                    if (client != null) {
                        try {
                            client.close();
                            log.debug("Closed bootstrap KubernetesClient for cluster {} (cause={})", key, cause);
                        } catch (Exception e) {
                            log.warn(
                                    "Failed to close bootstrap KubernetesClient for cluster {}: {}", key, e.toString());
                        }
                    }
                })
                .build();
        log.info(
                "AgentBootstrapKubeClient initialized (expireAfterAccess={}, maxSize={})",
                EXPIRE_AFTER_ACCESS,
                MAX_SIZE);
    }

    @PreDestroy
    void shutdown() {
        if (cache != null) {
            log.info("Shutting down AgentBootstrapKubeClient (size={})", cache.estimatedSize());
            cache.invalidateAll();
            cache.cleanUp();
        }
    }

    /**
     * Bootstrap fabric8 호출 — 401/403 발생 시 캐시를 invalidate 하고 1회 재시도.
     *
     * <p>Day-2 호출에는 사용하지 말 것. {@code io.aipaas.cluster.agent.runtime.KubeResourceService}
     * 가 agent stream 으로 routing 한다.
     */
    public <T> T execute(ClusterEntity cluster, Function<KubernetesClient, T> action) {
        KubernetesClient client = clientFor(cluster);
        try {
            return action.apply(client);
        } catch (KubernetesClientException e) {
            if (!isAuthFailure(e)) {
                throw e;
            }
            log.warn(
                    "Auth failure (code={}) on cluster {} bootstrap call, invalidating client and retrying once",
                    e.getCode(),
                    cluster.getId());
            invalidate(cluster.getId());
            KubernetesClient fresh = clientFor(cluster);
            return action.apply(fresh);
        }
    }

    /**
     * 반환값이 없는 변형. 401/403 재시도 의미는 {@link #execute(ClusterEntity, Function)} 와 동일.
     */
    public void executeVoid(ClusterEntity cluster, Consumer<KubernetesClient> action) {
        execute(cluster, client -> {
            action.accept(client);
            return null;
        });
    }

    /**
     * 클러스터 메타데이터가 바뀌었거나 인증이 만료되어 다음 사용 전 강제 갱신이 필요할 때 호출.
     */
    public void invalidate(String clusterId) {
        if (clusterId == null) {
            return;
        }
        cache.invalidate(clusterId);
    }

    /** 내부 helper — cache miss 시 새 client 빌드. {@code execute} / {@code executeVoid} 만 호출. */
    private KubernetesClient clientFor(ClusterEntity cluster) {
        return cache.get(cluster.getId(), key -> KubernetesClientFactory.buildClient(cluster));
    }

    private static boolean isAuthFailure(KubernetesClientException e) {
        int code = e.getCode();
        return code == 401 || code == 403;
    }
}
