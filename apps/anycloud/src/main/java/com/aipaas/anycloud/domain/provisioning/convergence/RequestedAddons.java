package com.aipaas.anycloud.domain.provisioning.convergence;

import com.aipaas.anycloud.domain.addon.model.AddonType;
import com.aipaas.anycloud.domain.cluster.model.GpuInstanceClassifier;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;

/** 프로비저닝 요청 플래그 → addon 카탈로그 id 매핑. */
public final class RequestedAddons {

    private RequestedAddons() {}

    /** key 는 {@code addons.yaml} 의 id, value 는 같은 항목의 type. */
    public static Map<String, AddonType> catalogEntries(VmClusterInternalRequestSnapshot spec) {
        Map<String, AddonType> entries = new LinkedHashMap<>();
        /*
         * GPU 노드를 골랐으면 드라이버 스택도 함께 올린다. 화면이 보내는 플래그에만 기대면
         * 실제로 그랬다 — GPU 인스턴스로 만들어도 operator 가 없어 GPU 를 쓸 수 없었다.
         * 명시로 끈 경우는 존중한다.
         */
        if (!Boolean.FALSE.equals(spec.getEnableGpuOperator())
                && (Boolean.TRUE.equals(spec.getEnableGpuOperator()) || hasGpuNodes(spec))) {
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

    /** 고른 인스턴스 타입으로 판정한다. 화면이 계산해 보내면 UI 를 우회했을 때 갈린다. */
    private static boolean hasGpuNodes(VmClusterInternalRequestSnapshot spec) {
        return GpuInstanceClassifier.isGpu(spec.getClusterProvider(), spec.getMasterVmSpec())
                || GpuInstanceClassifier.isGpu(spec.getClusterProvider(), spec.getWorkerVmSpec());
    }
}
