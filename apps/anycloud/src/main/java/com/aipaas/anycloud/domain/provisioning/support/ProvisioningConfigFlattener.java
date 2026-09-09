package com.aipaas.anycloud.domain.provisioning.support;

import com.aipaas.anycloud.domain.provisioning.api.request.ClusterSpecRequest;
import com.aipaas.anycloud.domain.provisioning.api.request.VmCreateRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 중첩 요청을 Pulumi stack config 의 평면 키로 편다.
 *
 * <p>Pulumi 의 config 는 문자열 평면 맵이다. 그 제약이 HTTP 계약까지 올라오면 안 되므로 경계를
 * 여기 하나로 둔다. 이 위는 타입 있는 객체, 이 아래는 {@code anycloud-k8s:*} 문자열이다.
 */
public final class ProvisioningConfigFlattener {

    private static final String NS = "anycloud-k8s:";

    private ProvisioningConfigFlattener() {}

    public static Map<String, String> flatten(VmCreateRequest request) {
        Map<String, String> out = new LinkedHashMap<>();
        putSpec(out, request.getSpec());
        putProviderSpec(out, request.getProviderSpec());
        return out;
    }

    private static void putSpec(Map<String, String> out, ClusterSpecRequest spec) {
        if (spec == null) {
            return;
        }
        put(out, "kubernetesVersion", spec.kubernetesVersion());
        put(out, "masterCount", spec.masterCount());
        put(out, "workerCount", spec.workerCount());
        put(out, "masterInstanceType", spec.masterInstanceType());
        put(out, "workerInstanceType", spec.workerInstanceType());
        put(out, "rootDiskSizeGb", spec.rootDiskSizeGb());
        put(out, "osImage", spec.osImage());
        put(out, "sshUser", spec.sshUser());
        put(out, "enableIngress", spec.enableIngress());
        put(out, "enableGpuOperator", spec.enableGpuOperator());
        put(out, "useSpot", spec.useSpot());

        ClusterSpecRequest.NetworkSpecRequest net = spec.network();
        if (net != null) {
            put(out, "vpcCidr", net.vpcCidr());
            put(out, "podCidr", net.podCidr());
            put(out, "serviceCidr", net.serviceCidr());
        }
    }

    /**
     * provider 별 스키마라 서버가 키를 모른다. Cluster API 의 {@code providerSpec} 과 같은 취급으로,
     * 검증은 provider 를 아는 계층(preflight, emitter)이 한다.
     */
    private static void putProviderSpec(Map<String, String> out, Map<String, Object> providerSpec) {
        if (providerSpec == null) {
            return;
        }
        providerSpec.forEach((key, value) -> put(out, "providerSpec." + key, value));
    }

    private static void put(Map<String, String> out, String key, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return;
        }
        out.put(NS + key, text);
    }
}
