package com.aipaas.anycloud.domain.provisioning.convergence;

import com.aipaas.anycloud.domain.addon.model.AddonType;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;

/** 프로비저닝 요청 플래그 → addon 카탈로그 id 매핑. */
public final class RequestedAddons {

    private RequestedAddons() {}

    /** key 는 {@code addons.yaml} 의 id, value 는 같은 항목의 type. */
    public static Map<String, AddonType> catalogEntries(VmClusterInternalRequestSnapshot spec) {
        Map<String, AddonType> entries = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(spec.getEnableGpuOperator())) {
            entries.put("nvidia-gpu-operator", AddonType.GENERIC);
        }
        if (Boolean.TRUE.equals(spec.getEnableIngress())) {
            entries.put("ingress-nginx", AddonType.INGRESS_NGINX);
        }
        // null 은 켜짐으로 읽지 않는다 — 이 값이 없던 시절의 클러스터가 "애드온 누락" 으로
        // DEGRADED 가 된다. 새 요청에는 기본값이 채워져 들어온다.
        if (Boolean.TRUE.equals(spec.getEnableMonitoring())) {
            entries.put("kube-prometheus-stack", AddonType.MONITORING);
        }
        return entries;
    }
}
