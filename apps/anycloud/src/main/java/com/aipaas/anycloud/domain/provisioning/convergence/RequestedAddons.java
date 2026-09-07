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
        return entries;
    }
}
