package com.aipaas.anycloud.domain.provisioning.support;

import com.aipaas.anycloud.domain.provisioning.api.request.ClusterSpecRequest;
import com.aipaas.anycloud.domain.provisioning.api.request.VmCreateRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 중첩 요청을 Pulumi stack config 의 평면 키로 변환.
 *
 * <p>Pulumi 의 config 는 문자열 평면 맵. 해당 제약이 HTTP 계약까지 전파되지 않도록 경계를 이
 * 클래스로 한정. 상위는 타입 보유 객체, 하위는 {@code anycloud-k8s:*} 문자열.
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
        put(out, "enableMonitoring", spec.enableMonitoring());
        put(out, "useSpot", spec.useSpot());

        ClusterSpecRequest.NetworkSpecRequest net = spec.network();
        if (net != null) {
            put(out, "vpcCidr", net.vpcCidr());
            put(out, "podCidr", net.podCidr());
            put(out, "serviceCidr", net.serviceCidr());
        }
    }

    /**
     * provider 별 스키마라 서버가 키를 인지하지 않음. Cluster API 의 {@code providerSpec} 과
     * 동일 취급이며 검증은 provider 를 인지하는 계층(preflight, emitter) 담당.
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
        /*
         * 목록은 쉼표로 잇는다. String.valueOf 를 그대로 쓰면 "[2200, 2201]" 이 되어 파서가
         * 대괄호를 숫자로 읽다 "숫자가 아닌 값: [2200" 으로 죽는다: Proxmox 의 sshPorts 가 그랬다.
         */
        String text = value instanceof java.util.Collection<?> items
                ? items.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","))
                : String.valueOf(value);
        if (text.isBlank()) {
            return;
        }
        out.put(NS + key, text);
    }
}
