package com.aipaas.anycloud.domain.kube;

/**
 * K8s namespace 정규화 util.
 *
 * <p>backend service 의 K8s 호출 path 에서 반복적으로 등장하던 idiom
 * {@code (ns == null || ns.isBlank()) ? "default" : ns} 의 중복 (ChartServiceImpl 5+ 회 +
 * KubeServiceImpl 6+ 회) 을 한 곳으로 모음.
 *
 * <p>의미: namespace 입력이 null / 공백 / 빈 문자열 → K8s default namespace ("default"). 그 외엔
 * 입력값 그대로. 단순 single-line idiom 이지만 호출 site 가 분산 — typo 위험 / 변경
 * (예: 향후 system default namespace 변경) 시 N 곳 동시 수정 필요 회피.
 */
public final class Namespaces {

    /** K8s 의 명시되지 않은 namespace 의 표준 default 값 — kubectl 도 동일. */
    public static final String DEFAULT = "default";

    private Namespaces() {
        // util — 인스턴스화 금지.
    }

    /**
     * 입력 namespace 가 null / 공백 → "default", 그 외 입력 그대로 반환.
     *
     * <p>호출 예:
     * <pre>{@code
     *   String ns = Namespaces.defaultIfBlank(rawNamespace);
     *   helmReleaseService.install(clusterName, releaseName, chart, ..., ns, ...);
     * }</pre>
     */
    public static String defaultIfBlank(String namespace) {
        return (namespace == null || namespace.isBlank()) ? DEFAULT : namespace;
    }
}
