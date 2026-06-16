package com.aipaas.anycloud.domain.cluster.mapper;

import com.aipaas.anycloud.domain.cluster.api.request.CreateClusterRequest;
import com.aipaas.anycloud.domain.cluster.model.RegisteredClusterSpec;
import com.aipaas.anycloud.domain.cluster.model.VmClusterSpec;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link CreateClusterRequest#getSpec()} 의 약타입 {@code Map<String, Object>} 를 service layer 의
 * typed record ({@link VmClusterSpec} / {@link RegisteredClusterSpec}) 로 변환.
 * mapper 가 단일 변환 지점 — typo / 누락 field 는 즉시 fail.
 */
public final class ClusterSpecMapper {

    private ClusterSpecMapper() {}

    /** vm source spec 변환. provider/region/environment 필수, credentialId/config/hasGpuNodes/useSpot/osImage 선택. */
    public static VmClusterSpec toVm(Map<String, Object> spec) {
        String provider = requireString(spec, "provider");
        String region = requireString(spec, "region");
        String environment = requireString(spec, "environment");
        String credentialId = optionalString(spec, "credentialId");
        Map<String, String> config = optionalStringMap(spec, "config");
        Boolean hasGpuNodes = optionalBoolean(spec, "hasGpuNodes");
        // Spot + custom OS image typed 필드.
        Boolean useSpot = optionalBoolean(spec, "useSpot");
        String osImage = optionalString(spec, "osImage");
        Integer rootDiskSizeGb = optionalInteger(spec, "rootDiskSizeGb");
        return new VmClusterSpec(
                provider, region, environment, credentialId, config, hasGpuNodes, useSpot, osImage, rootDiskSizeGb);
    }

    /**
     * registered source spec 변환. provider/clusterType 필수, 나머지 선택.
     *
     * <p>apiServerUrl/IP, serverCA, clientCA/Key/Token, monitServerURL
     * 모두 제거. cluster-agent dial-in 이 source-of-truth — 등록 body 는 metadata 만.
     */
    public static RegisteredClusterSpec toRegistered(Map<String, Object> spec) {
        String provider = requireString(spec, "provider");
        String clusterType = requireString(spec, "clusterType");
        // addons 는 Map → ClusterSpec 마이그레이션 까지 미사용 path.
        // 본 mapper 는 weak-typed Map 진입점이므로 addons 는 null 로 둠 — frontend 가 typed body
        // (RegisteredClusterSpec) 로 호출 시에만 addons 전달됨.
        return new RegisteredClusterSpec(
                provider, clusterType, optionalString(spec, "description"), optionalBoolean(spec, "hasGpuNodes"), null);
    }

    private static String requireString(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("spec." + key + " is required");
        }
        return String.valueOf(v);
    }

    private static String optionalString(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * Boolean field 추출. JSON 의 {@code true}/{@code false} 외에도 string {@code "true"}/{@code
     * "false"} 도 수용 (운영자가 curl 로 spec map 직접 작성하는 경우 대비). 키 없거나 null → null.
     */
    private static Boolean optionalBoolean(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        return Boolean.parseBoolean(s);
    }

    /**
     * Integer field 추출. JSON number 또는 string {@code "50"} 수용. 키 없거나 null / 파싱 실패 → null
     * (provider 기본값 사용). 음수/0 은 그대로 통과 — 하위(Go defaults)에서 정규화.
     */
    private static Integer optionalInteger(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, String> optionalStringMap(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        if (!(v instanceof Map<?, ?> m)) {
            return null;
        }
        Map<String, String> typed = new HashMap<>();
        m.forEach((k, val) -> typed.put(String.valueOf(k), val == null ? null : String.valueOf(val)));
        return typed;
    }
}
