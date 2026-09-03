package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import com.aipaas.anycloud.domain.addon.ClusterAddonEntity;
import com.aipaas.anycloud.domain.addon.ClusterAddonRepository;
import com.aipaas.anycloud.domain.addon.model.AddonState;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterBootstrapSnapshotService;
import com.aipaas.anycloud.domain.provisioning.convergence.ComponentHealth;
import com.aipaas.anycloud.domain.provisioning.convergence.ConvergenceSignal;
import com.aipaas.anycloud.domain.provisioning.convergence.RequestedAddonInspector;
import com.aipaas.anycloud.domain.provisioning.convergence.Requirement;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 프로비저닝 요청이 함축하는 addon 의 설치 상태를 수렴 신호로 변환.
 *
 * <p>어떤 addon 이 "요청된" 것인지는 별도 컬럼 없이 요청 스냅샷에서 매번 다시 계산한다. 스냅샷은
 * {@code vm_cluster.request_config} 에 이미 영속화되어 있어 추가 저장이 필요 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestedAddonInspectorImpl implements RequestedAddonInspector {

    /** 설치가 끝나지 않은 상태. 실패로 판정하면 설치 중인 것을 미충족으로 보고하게 된다. */
    private static final Set<AddonState> IN_FLIGHT =
            Set.of(AddonState.PENDING, AddonState.ENQUEUED, AddonState.INSTALLING);

    private final ClusterAddonRepository addonRepository;
    private final VmClusterBootstrapSnapshotService snapshotService;

    @Override
    public List<ConvergenceSignal> inspect(VmClusterEntity vmCluster) {
        String clusterId = vmCluster.getClusterId();
        if (clusterId == null || clusterId.isBlank()) {
            return List.of();
        }
        Set<String> requested = requestedCatalogIds(snapshotService.read(vmCluster.getRequestConfig()));
        if (requested.isEmpty()) {
            return List.of();
        }
        Map<String, ClusterAddonEntity> installed;
        try {
            installed = new LinkedHashMap<>();
            for (ClusterAddonEntity addon : addonRepository.findByClusterId(clusterId)) {
                if (addon.getCatalogId() != null) {
                    installed.putIfAbsent(addon.getCatalogId(), addon);
                }
            }
        } catch (Exception e) {
            log.warn("요청 addon 조회 실패 cluster={}: {}", vmCluster.getClusterName(), e.toString());
            return List.of();
        }

        List<ConvergenceSignal> signals = new ArrayList<>();
        for (String catalogId : requested) {
            signals.add(toSignal(catalogId, installed.get(catalogId)));
        }
        return signals;
    }

    private ConvergenceSignal toSignal(String catalogId, ClusterAddonEntity addon) {
        if (addon == null) {
            // agent 미연결이면 auto-enroll 이 아직 안 돌았다. 미설치로 단정할 수 없다.
            return new ConvergenceSignal(
                    catalogId, Requirement.REQUIRED, ComponentHealth.UNKNOWN, "addon 이 아직 등록되지 않았습니다");
        }
        AddonState state = addon.getState();
        if (state == AddonState.SUCCEEDED) {
            return new ConvergenceSignal(catalogId, Requirement.REQUIRED, ComponentHealth.READY, null);
        }
        if (IN_FLIGHT.contains(state)) {
            return new ConvergenceSignal(
                    catalogId, Requirement.REQUIRED, ComponentHealth.UNKNOWN, "설치 진행 중 (" + state + ")");
        }
        return new ConvergenceSignal(
                catalogId,
                Requirement.REQUIRED,
                ComponentHealth.NOT_READY,
                addon.getLastError() != null ? addon.getLastError() : "addon state=" + state);
    }

    /** 요청 플래그 → 카탈로그 id. 카탈로그 항목이 바뀌면 여기만 고친다. */
    private Set<String> requestedCatalogIds(VmClusterInternalRequestSnapshot spec) {
        Set<String> ids = new LinkedHashSet<>();
        if (Boolean.TRUE.equals(spec.getEnableGpuOperator())) {
            ids.add("nvidia-gpu-operator");
        }
        if (Boolean.TRUE.equals(spec.getEnableIngress())) {
            ids.add("ingress-nginx");
        }
        return ids;
    }
}
