package com.aipaas.anycloud.domain.helmrepo.internal;

import com.aipaas.anycloud.domain.agent.policy.ClusterPolicyBootstrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Backend startup 직후 (HelmRepoAutoSeedRunner 보다 늦게) ACTIVE cluster
 * 들에게 helm_repositories broadcast.
 *
 * <p>Event-driven broadcast 는 backend 가 실행 중인 동안의 CRUD 만 catch. backend 재기동
 * 사이에 발생한 변화 (seed / DB 직접 수정) 가 누락된 cluster 들이 있을 수 있어 boot 후 한 번 full
 * push 로 회복. 멱등 — 동일 값이면 agent 측 no-op.
 *
 * <p>{@link HelmRepoAutoSeedRunner} 의 ApplicationReady 처리가 완료된 후 본 runner 가 실행되도록
 * {@code @Order(LOWEST_PRECEDENCE)} 사용. seed 가 disable 이어도 본 runner 는 동작 (auto-seed
 * 와 분리된 책임 — DB 의 현재 상태를 cluster 로 push).
 *
 * <p>실패는 swallow + log — {@link ClusterPolicyBootstrapper#broadcastHelmRepoChange} 가 per-cluster
 * 실패를 이미 처리.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE)
public class HelmRepoBootBroadcastRunner {

    private final ClusterPolicyBootstrapper clusterPolicyBootstrapper;

    @EventListener(ApplicationReadyEvent.class)
    public void broadcastOnReady() {
        log.info("HelmRepoBootBroadcastRunner: triggering boot-time helm_repositories broadcast — "
                + "회복 시나리오 (재기동 중 발생한 CRUD 누락 보완)");
        try {
            clusterPolicyBootstrapper.broadcastHelmRepoChange();
        } catch (Exception e) {
            // @Async method 호출 자체는 즉시 반환 — 여기 throw 가 거의 없지만 안전망.
            log.warn("HelmRepoBootBroadcastRunner: broadcast trigger failed: {}", e.toString());
        }
    }
}
