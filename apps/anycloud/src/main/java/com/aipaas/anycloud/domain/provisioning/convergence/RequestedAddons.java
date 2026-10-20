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
         * GPU 노드 선택 시 드라이버 스택도 함께 설치. 화면 전송 플래그에만 의존하면 GPU
         * 인스턴스 생성 후에도 operator 부재로 GPU 사용 불가. 명시적 비활성화는 유지.
         */
        if (!Boolean.FALSE.equals(spec.getEnableGpuOperator())
                && (Boolean.TRUE.equals(spec.getEnableGpuOperator()) || hasGpuNodes(spec))) {
            entries.put("nvidia-gpu-operator", AddonType.GENERIC);
        }
        if (Boolean.TRUE.equals(spec.getEnableIngress())) {
            entries.put("ingress-nginx", AddonType.INGRESS_NGINX);
        }
        // null 을 활성으로 해석하지 않음. 해당 값 도입 이전 클러스터가 애드온 누락으로
        // DEGRADED 전이. 신규 요청에는 기본값이 채워져 수신.
        if (Boolean.TRUE.equals(spec.getEnableMonitoring())) {
            entries.put("kube-prometheus-stack", AddonType.MONITORING);
        }
        return entries;
    }

    /** 선택한 인스턴스 타입 기준 판정. 화면에서 계산해 전송하면 UI 우회 시 결과 불일치. */
    private static boolean hasGpuNodes(VmClusterInternalRequestSnapshot spec) {
        return GpuInstanceClassifier.isGpu(spec.getClusterProvider(), spec.getMasterVmSpec())
                || GpuInstanceClassifier.isGpu(spec.getClusterProvider(), spec.getWorkerVmSpec());
    }
}
