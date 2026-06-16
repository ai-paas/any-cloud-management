package com.aipaas.anycloud.domain.addon;

import com.aipaas.anycloud.domain.addon.internal.AddonInstallPublisher;
import com.aipaas.anycloud.domain.addon.model.AddonState;
import com.aipaas.anycloud.domain.operation.OperationEntity;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Addon enqueue orchestration — 여러 caller (cluster ACTIVE hook, REST API add/retry, backfill
 * admin endpoint) 가 공유하는 single entry point.
 *
 * <p>책임:
 * <ol>
 *   <li>대상 addon state 검증 — 이미 INSTALLING/SUCCEEDED 면 skip (idempotency).</li>
 *   <li>OperationEntity 생성 (LRO) — frontend SSE 구독용 id 반환.</li>
 *   <li>addon row state → ENQUEUED + lastOperationId set.</li>
 *   <li>{@link AddonInstallPublisher} 호출 → RabbitMQ broker 적재.</li>
 * </ol>
 *
 * <p>본 service 는 publisher 직접 호출 대신의 thin layer — caller 가 RabbitMQ 의존 / Operation DB
 * 의존을 모두 잊고 cluster/addon id 만 다루도록.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "addon-workflow", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AddonOrchestrator {

    private final ClusterAddonRepository addonRepository;
    private final AddonInstallPublisher publisher;
    private final OperationService operationService;

    /**
     * Cluster 의 모든 enqueue-eligible addon 을 install queue 로 publish.
     *
     * <p>대상: enabled=true AND state ∈ {PENDING, FAILED}. INSTALLING/SUCCEEDED/DELETING 은 skip.
     * cluster ACTIVE listener 가 호출 — 신규 cluster + 기존 cluster (backfill) 동일 동작.
     *
     * @return enqueue 된 addon row 개수.
     */
    @Transactional
    public int enqueuePendingForCluster(String clusterId) {
        List<ClusterAddonEntity> eligible = addonRepository.findByClusterIdAndStateInAndEnabledTrue(
                clusterId, List.of(AddonState.PENDING, AddonState.FAILED));
        int enqueued = 0;
        for (ClusterAddonEntity addon : eligible) {
            enqueueInstallSingle(addon);
            enqueued++;
        }
        log.info("AddonOrchestrator: enqueued {} addon(s) for cluster={}", enqueued, clusterId);
        return enqueued;
    }

    /** 단일 addon enqueue — REST POST 또는 retry 시점에 호출. */
    @Transactional
    public OperationEntity enqueueInstall(String addonId) {
        ClusterAddonEntity addon = addonRepository
                .findById(addonId)
                .orElseThrow(() -> new IllegalArgumentException("addon not found: " + addonId));
        if (addon.getState().isInFlight()) {
            log.info("AddonOrchestrator: addon {} already in-flight (state={}) — skip", addonId, addon.getState());
            return findOperation(addon.getLastOperationId());
        }
        return enqueueInstallSingle(addon);
    }

    /** uninstall enqueue — DELETE endpoint 가 호출. */
    @Transactional
    public OperationEntity enqueueUninstall(String addonId) {
        ClusterAddonEntity addon = addonRepository
                .findById(addonId)
                .orElseThrow(() -> new IllegalArgumentException("addon not found: " + addonId));
        OperationEntity op = operationService.start(
                OperationType.UNINSTALL_ADDON, "addon", addonId, "{\"clusterId\":\"" + addon.getClusterId() + "\"}", 1);
        addon.setState(AddonState.DELETING);
        addon.setLastOperationId(op.getId());
        addonRepository.save(addon);
        publisher.enqueueUninstall(addon.getClusterId(), addonId, op.getId());
        return op;
    }

    // ---- internal ----

    private OperationEntity enqueueInstallSingle(ClusterAddonEntity addon) {
        OperationEntity op = operationService.start(
                OperationType.INSTALL_ADDON,
                "addon",
                addon.getId(),
                "{\"clusterId\":\"" + addon.getClusterId() + "\",\"chart\":\"" + addon.getChartName() + "\"}",
                1);
        addon.setState(AddonState.ENQUEUED);
        addon.setLastOperationId(op.getId());
        addonRepository.save(addon);
        publisher.enqueueInstall(addon.getClusterId(), addon.getId(), op.getId());
        return op;
    }

    private OperationEntity findOperation(String opId) {
        if (opId == null) return null;
        return operationService.findById(opId).orElse(null);
    }
}
