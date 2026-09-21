package com.aipaas.anycloud.domain.addon.internal;

import com.aipaas.anycloud.common.error.exception.provisioning.StateConflictException;
import com.aipaas.anycloud.domain.addon.AddonOrchestrator;
import com.aipaas.anycloud.domain.addon.AddonService;
import com.aipaas.anycloud.domain.addon.ClusterAddonEntity;
import com.aipaas.anycloud.domain.addon.ClusterAddonRepository;
import com.aipaas.anycloud.domain.addon.api.response.AddonStatusResponse;
import com.aipaas.anycloud.domain.addon.mapper.AddonMapper;
import com.aipaas.anycloud.domain.addon.model.AddonSpec;
import com.aipaas.anycloud.domain.addon.model.AddonState;
import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.cluster.model.ClusterStatus;
import com.aipaas.anycloud.domain.operation.OperationEntity;
import io.aipaas.cluster.agent.runtime.HelmReleaseService;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AddonServiceImpl implements AddonService {

    private final ClusterAddonRepository addonRepository;
    private final ClusterRepository clusterRepository;
    private final AddonSpecResolver resolver;
    private final AddonOrchestrator orchestrator;
    private final AddonMapper addonMapper;
    private final HelmReleaseService helmReleaseService;

    @Override
    @Transactional(readOnly = true)
    public List<AddonStatusResponse> list(String clusterId) {
        ClusterStatus status = clusterRepository
                .findById(clusterId)
                .orElseThrow(() -> new EntityNotFoundException("Cluster not found: " + clusterId))
                .getStatus();
        return addonRepository.findByClusterId(clusterId).stream()
                .map(a -> addonMapper.toResponse(a, status))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AddonStatusResponse get(String clusterId, String addonId) {
        ClusterAddonEntity addon = requireAddon(clusterId, addonId);
        ClusterStatus status = clusterRepository
                .findById(clusterId)
                .map(ClusterEntity::getStatus)
                .orElse(null);
        return addonMapper.toResponse(addon, status);
    }

    @Override
    @Transactional
    public AddonStatusResponse create(String clusterId, AddonSpec spec) {
        ClusterEntity cluster = clusterRepository
                .findById(clusterId)
                .orElseThrow(() -> new EntityNotFoundException("Cluster not found: " + clusterId));
        ClusterStatus status = cluster.getStatus();
        ClusterAddonEntity entity = resolver.resolve(spec, clusterId);
        addonRepository
                .findByClusterIdAndNamespaceAndReleaseName(clusterId, entity.getNamespace(), entity.getReleaseName())
                .ifPresent(existing -> {
                    throw new StateConflictException(String.format(
                            "Addon 이미 존재 (cluster=%s namespace=%s release=%s state=%s id=%s) — "
                                    + "DELETE 후 재생성 또는 retry endpoint 사용.",
                            clusterId,
                            entity.getNamespace(),
                            entity.getReleaseName(),
                            existing.getState(),
                            existing.getId()));
                });
        entity.setState(AddonState.PENDING);
        ClusterAddonEntity saved = addonRepository.save(entity);
        log.info(
                "AddonService: create addon={} cluster={} type={} state=PENDING",
                saved.getId(),
                clusterId,
                saved.getAddonType());

        /*
         * dcgm-exporter 를 따라 붙이지 않는다. gpu-operator 가 DCGM 을 함께 올리므로 두 벌이
         * 되고, 뒤에 뜬 쪽은 GPU 를 잡지 못해 CrashLoopBackOff 로 남는다. operator 없이 metric
         * 만 뽑으려면 카탈로그에서 직접 고른다.
         */

        // enqueue 조건: cluster 가 ACTIVE 이거나, agent gRPC 세션이 실제 active.
        // persisted ClusterStatus enum 이 stale 해도 (agent 등록 직후 status 미동기 등) 지금 agent 에
        // 도달 가능하면 enqueue — addon 이 다음 reconnect/backfill 까지 PENDING 으로 막히는 것을 방지.
        // 아니면 listener 가 cluster ACTIVE 전환 시 enqueue, 또는 :enqueue 로 수동 backfill.
        boolean agentReachable = status == ClusterStatus.ACTIVE || helmReleaseService.isActiveFor(clusterId);
        if (Boolean.TRUE.equals(saved.getEnabled()) && agentReachable) {
            orchestrator.enqueueInstall(saved.getId());
            saved = addonRepository.findById(saved.getId()).orElse(saved);
        }
        return addonMapper.toResponse(saved, status);
    }

    @Override
    @Transactional
    public OperationEntity retry(String clusterId, String addonId) {
        ClusterAddonEntity addon = requireAddon(clusterId, addonId);
        if (!addon.getState().isRetryable()) {
            throw new StateConflictException("addon state " + addon.getState() + " is not retryable (only FAILED).");
        }
        return orchestrator.enqueueInstall(addonId);
    }

    @Override
    @Transactional
    public OperationEntity delete(String clusterId, String addonId) {
        requireAddon(clusterId, addonId);
        return orchestrator.enqueueUninstall(addonId);
    }

    @Override
    @Transactional
    public int reenqueueAllForCluster(String clusterId) {
        assertClusterExists(clusterId);
        return orchestrator.enqueuePendingForCluster(clusterId);
    }

    private void assertClusterExists(String clusterId) {
        if (!clusterRepository.existsById(clusterId)) {
            throw new EntityNotFoundException("Cluster not found: " + clusterId);
        }
    }

    private ClusterAddonEntity requireAddon(String clusterId, String addonId) {
        ClusterAddonEntity addon = addonRepository
                .findById(addonId)
                .orElseThrow(() -> new EntityNotFoundException("Addon not found: " + addonId));
        if (!addon.getClusterId().equals(clusterId)) {
            throw new EntityNotFoundException("Addon " + addonId + " does not belong to cluster " + clusterId);
        }
        return addon;
    }
}
