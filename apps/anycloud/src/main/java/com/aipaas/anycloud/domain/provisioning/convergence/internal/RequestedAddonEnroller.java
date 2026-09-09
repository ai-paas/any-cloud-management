package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import com.aipaas.anycloud.domain.addon.AddonService;
import com.aipaas.anycloud.domain.addon.model.AddonSpec;
import com.aipaas.anycloud.domain.addon.model.AddonType;
import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterBootstrapSnapshotService;
import com.aipaas.anycloud.domain.provisioning.convergence.RequestedAddons;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 프로비저닝 요청이 함축하는 addon 을 등록. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestedAddonEnroller {

    private final AddonService addonService;
    private final VmClusterBootstrapSnapshotService snapshotService;

    /** 예외를 던지지 않는다 — addon 등록 실패가 BOOTSTRAP 을 실패시키면 안 된다. */
    public void enroll(VmClusterEntity vmCluster) {
        String clusterId = vmCluster.getClusterId();
        if (clusterId == null || clusterId.isBlank()) {
            return;
        }
        Map<String, AddonType> requested;
        try {
            requested = RequestedAddons.catalogEntries(snapshotService.read(vmCluster.getRequestConfig()));
        } catch (Exception e) {
            log.warn("요청 addon 산출 실패 cluster={}: {}", vmCluster.getClusterName(), e.toString());
            return;
        }
        for (Map.Entry<String, AddonType> entry : requested.entrySet()) {
            try {
                addonService.create(clusterId, catalogOnlySpec(entry.getKey(), entry.getValue()));
                log.info("요청 addon 등록 cluster={} catalog_id={}", vmCluster.getClusterName(), entry.getKey());
            } catch (Exception e) {
                // 중복 등록은 정상 — AddonService.create 가 멱등하다. 나머지는 조정 루프가 드러낸다.
                log.warn(
                        "요청 addon 등록 실패 cluster={} catalog_id={}: {}",
                        vmCluster.getClusterName(),
                        entry.getKey(),
                        e.toString());
            }
        }
    }

    private AddonSpec catalogOnlySpec(String catalogId, AddonType type) {
        return new AddonSpec(type, catalogId, null, null, null, null, null, null, null, true);
    }
}
