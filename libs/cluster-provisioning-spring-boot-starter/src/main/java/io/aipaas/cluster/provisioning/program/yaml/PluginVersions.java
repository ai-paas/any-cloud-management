package io.aipaas.cluster.provisioning.program.yaml;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * provider 플러그인 버전 고정.
 *
 * <p>타입 SDK 는 jar 이 버전을 담아 자동으로 고정됐다. YAML 은 {@code aws:ec2/instance:Instance} 만
 * 쓰므로 캐시가 비면 그날의 latest 를 받는다. 환경마다 다른 버전이 뜨고, 며칠 뒤 재현되지 않는다.
 *
 * <p>값의 출처는 {@code PULUMI_PLUGINS} 환경변수다. Dockerfile 이 같은 ARG 로 플러그인을 설치하므로
 * 설치본과 프로그램이 어긋나지 않는다.
 */
public final class PluginVersions {

    /** Dockerfile 의 {@code PULUMI_PLUGINS} 기본값과 같아야 한다. */
    static final String DEFAULT =
            "aws:7.44.0 gcp:9.36.1 azure-native:3.27.0 oci:4.22.0 openstack:5.5.1 proxmoxve:8.6.0 tls:5.6.0";

    private static final String ENV_KEY = "PULUMI_PLUGINS";

    private final Map<String, String> byPackage;

    private PluginVersions(Map<String, String> byPackage) {
        this.byPackage = byPackage;
    }

    public static PluginVersions fromEnvironment() {
        String raw = System.getenv(ENV_KEY);
        return parse(raw == null || raw.isBlank() ? DEFAULT : raw);
    }

    /** {@code "aws:7.44.0 tls:5.6.0"} 형식. 값이 깨지면 고정이 조용히 풀리므로 즉시 실패한다. */
    static PluginVersions parse(String spec) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String token : spec.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            int sep = token.lastIndexOf(':');
            if (sep <= 0 || sep == token.length() - 1) {
                throw new IllegalArgumentException(ENV_KEY + " 형식 오류 — name:version 이어야 한다: " + token);
            }
            map.put(token.substring(0, sep), token.substring(sep + 1));
        }
        return new PluginVersions(map);
    }

    /**
     * 타입 토큰이 속한 패키지의 버전. 모르는 패키지면 {@code null} 이고 호출자가 고정을 생략한다.
     *
     * <p>{@code azure-native:network:Subnet} 처럼 패키지 이름에 하이픈이 들어가고,
     * {@code aws:ec2/instance:Instance} 처럼 경로가 섞인다. 첫 콜론 앞이 패키지다.
     */
    String forType(String typeToken) {
        if (typeToken == null) return null;
        int sep = typeToken.indexOf(':');
        return sep <= 0 ? null : byPackage.get(typeToken.substring(0, sep));
    }

    Map<String, String> asMap() {
        return Map.copyOf(byPackage);
    }
}
