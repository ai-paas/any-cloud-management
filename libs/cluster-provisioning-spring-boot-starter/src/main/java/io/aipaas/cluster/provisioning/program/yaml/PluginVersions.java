package io.aipaas.cluster.provisioning.program.yaml;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * provider 플러그인 버전 고정.
 *
 * <p>타입 SDK 는 jar 이 버전을 포함해 자동 고정. YAML 은 {@code aws:ec2/instance:Instance} 만
 * 사용하므로 캐시가 비면 해당 시점 latest 를 수신. 환경별로 다른 버전이 설치되고 재현 불가.
 *
 * <p>값의 출처는 {@code PULUMI_PLUGINS} 환경변수. Dockerfile 이 동일 ARG 로 플러그인을 설치하므로
 * 설치본과 프로그램 버전 일치.
 */
public final class PluginVersions {

    /** Dockerfile 의 {@code PULUMI_PLUGINS} 기본값과 동일 필요. */
    static final String DEFAULT =
            "aws:7.44.0 gcp:9.36.1 oci:4.22.0 openstack:5.5.1 proxmoxve:8.6.0 alicloud:3.108.0 tls:5.6.0";

    private static final String ENV_KEY = "PULUMI_PLUGINS";

    /**
     * get.pulumi.com 에 없는 플러그인의 배포처. {@code Dockerfile.pulumi} 의
     * {@code PULUMI_PLUGIN_SERVERS} 와 동일 값.
     *
     * <p>플러그인 캐시는 볼륨이라 이미지 포함분이 유지되지 않음. 선언 부재 시 런타임 자동 설치가
     * 기본 주소로 요청해 403 응답. 오류 메시지가 플러그인 설치 요구로만 표시돼 배포처 문제로
     * 식별되지 않음.
     */
    private static final Map<String, String> DOWNLOAD_URLS =
            Map.of("proxmoxve", "github://api.github.com/muhlba91/pulumi-proxmoxve");

    private final Map<String, String> byPackage;

    private PluginVersions(Map<String, String> byPackage) {
        this.byPackage = byPackage;
    }

    public static PluginVersions fromEnvironment() {
        String raw = System.getenv(ENV_KEY);
        return parse(raw == null || raw.isBlank() ? DEFAULT : raw);
    }

    /** {@code "aws:7.44.0 tls:5.6.0"} 형식. 값 손상 시 버전 고정이 무음 해제되므로 즉시 실패. */
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
     * 타입 토큰이 속한 패키지의 버전. 미등록 패키지는 {@code null} 이며 호출자가 고정 생략.
     *
     * <p>{@code azure-native:network:Subnet} 처럼 패키지 이름에 하이픈이 포함되고,
     * {@code aws:ec2/instance:Instance} 처럼 경로가 혼재. 첫 콜론 앞이 패키지명.
     */
    String forType(String typeToken) {
        if (typeToken == null) return null;
        int sep = typeToken.indexOf(':');
        return sep <= 0 ? null : byPackage.get(typeToken.substring(0, sep));
    }

    /** 타입 토큰이 속한 패키지의 배포처. 기본 주소로 받을 수 있으면 {@code null}. */
    static String downloadUrlForType(String typeToken) {
        if (typeToken == null) return null;
        int sep = typeToken.indexOf(':');
        return sep <= 0 ? null : DOWNLOAD_URLS.get(typeToken.substring(0, sep));
    }

    Map<String, String> asMap() {
        return Map.copyOf(byPackage);
    }
}
