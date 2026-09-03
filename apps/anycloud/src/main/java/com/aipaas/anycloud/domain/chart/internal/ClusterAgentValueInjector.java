package com.aipaas.anycloud.domain.chart.internal;

import com.aipaas.anycloud.domain.agent.AgentProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Helm chart 의 values 에 backend 측 필수값을 자동 주입.
 * <p>
 * 대상 chart: {@code <repo>/cluster-agent}. 다른 chart 는 pass-through.
 * <p>
 * 자동 주입 항목 — 둘 다 사용자가 명시하면 그 값이 우선:
 * <ul>
 *   <li>{@code backend.grpcAddr} — 원천 {@code agent.grpc.public-endpoint}
 *       (env {@code ANYCLOUD_AGENT_GRPC_PUBLIC_ENDPOINT})</li>
 *   <li>{@code image.repository} / {@code image.tag} — 원천 {@code agent.manifest.image}
 *       (env {@code ANYCLOUD_AGENT_IMAGE}). 형식 {@code <repo>:<tag>}.
 *       registry:port 케이스는 last-colon-after-last-slash 규칙으로 안전 분리.</li>
 * </ul>
 * <p>
 * 적용 위치: ChartServiceImpl.deployChartFromYaml 의 최상단. JSON values 객체 /
 * valuesYaml 문자열 / multipart file 어느 경로로 들어와도 본 컴포넌트를 거침.
 */
@Slf4j
@Component
public class ClusterAgentValueInjector {

    /**
     * cluster-agent chart 의 식별 — repo/name 의 name 부분이 정확히 일치할 때만 주입.
     * private chartmuseum 에 본 chart 가 다른 이름으로 publish 된 경우 별도 설정으로 확장 가능.
     */
    private static final String CLUSTER_AGENT_CHART_NAME = "cluster-agent";

    /** 6개 @Value 분산 inject → AgentProperties 단일 진입점. */
    private final AgentProperties agentProperties;

    public ClusterAgentValueInjector(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
    }

    private String defaultBackendGrpcAddr() {
        return agentProperties.grpc().publicEndpoint();
    }

    private String defaultImageRef() {
        return agentProperties.manifest().image();
    }

    private boolean defaultTlsEnabled() {
        return agentProperties.grpc().tls().enabled();
    }

    private String defaultTlsCaCert() {
        return agentProperties.grpc().tls().caCert();
    }

    private String defaultTlsServerName() {
        return agentProperties.grpc().tls().serverName();
    }

    private boolean defaultTlsInsecureSkipVerify() {
        return agentProperties.grpc().tls().insecureSkipVerify();
    }

    /**
     * cluster-agent chart 면 values 를 enrich, 다른 chart 는 원본 그대로 반환.
     *
     * @param chartName  cluster 가 아닌 chart 이름 (repo prefix 제외)
     * @param valuesYaml 사용자가 보낸 raw YAML (null/empty 가능)
     * @return 주입된 YAML (cluster-agent + 변경 발생) 또는 입력 그대로
     */
    public String enrich(String chartName, String valuesYaml) {
        if (!CLUSTER_AGENT_CHART_NAME.equalsIgnoreCase(chartName)) {
            return valuesYaml;
        }

        Map<String, Object> values = parseYamlToMap(valuesYaml);

        boolean grpcInjected = ensureBackendGrpcAddr(values);
        boolean imageInjected = ensureImageRefs(values);
        boolean tlsInjected = ensureBackendTls(values);

        if (!grpcInjected && !imageInjected && !tlsInjected) {
            log.debug("cluster-agent values: all overridable fields client-provided — pass-through");
            return valuesYaml; // 변경 없음 → 원본 YAML 보존 (포맷/주석 유지)
        }

        String dumped = dumpYaml(values);
        log.info(
                "cluster-agent values injected — grpcAddr={}, image={}, tls.enabled={} (chart values 우선 규칙)",
                grpcInjected ? defaultBackendGrpcAddr() : "(client)",
                imageInjected ? defaultImageRef() : "(client)",
                tlsInjected ? defaultTlsEnabled() : "(client)");
        return dumped;
    }

    /* ---------- internals ---------- */

    /**
     * @return true 면 새로 주입함, false 면 사용자가 이미 채워둠.
     */
    @SuppressWarnings("unchecked")
    private boolean ensureBackendGrpcAddr(Map<String, Object> values) {
        Map<String, Object> backend = ensureMap(values, "backend");
        Object existing = backend.get("grpcAddr");
        if (existing instanceof String s && !s.isBlank()) {
            return false;
        }
        backend.put("grpcAddr", defaultBackendGrpcAddr());
        return true;
    }

    /**
     * backend.tls.* 자동 주입 — backend 의 agent.grpc.tls.enabled 가 true 일 때만 chart values 에
     * 주입. 사용자가 명시한 키는 보존.
     *
     * <p>backend TLS 비활성 (default) 면 본 메서드는 no-op — chart 의 default values
     * (backend.tls.enabled=false) 가 그대로 사용됨. 사용자가 명시했어도 backend 가 TLS off 면
     * 사용자 의도 우선 — 무엇이든 노출.
     *
     * @return true 면 변경 발생.
     */
    @SuppressWarnings("unchecked")
    private boolean ensureBackendTls(Map<String, Object> values) {
        if (!defaultTlsEnabled()) {
            return false; // backend TLS 비활성 → chart default 그대로 (no injection)
        }
        Map<String, Object> backend = ensureMap(values, "backend");
        Map<String, Object> tls = ensureMap(backend, "tls");
        boolean changed = false;
        if (!tls.containsKey("enabled")) {
            tls.put("enabled", true);
            changed = true;
        }
        String caCert = defaultTlsCaCert();
        if (!tls.containsKey("caCert") && caCert != null && !caCert.isBlank()) {
            tls.put("caCert", caCert);
            changed = true;
        }
        String serverName = defaultTlsServerName();
        if (!tls.containsKey("serverName") && serverName != null && !serverName.isBlank()) {
            tls.put("serverName", serverName);
            changed = true;
        }
        if (!tls.containsKey("insecureSkipVerify") && defaultTlsInsecureSkipVerify()) {
            tls.put("insecureSkipVerify", true);
            changed = true;
        }
        return changed;
    }

    /**
     * image.repository / image.tag 가 둘 다 비어있을 때만 주입.
     * 사용자가 둘 중 하나만 명시했어도 그 의도 존중 — repository 만 주고 tag 미명시 면 chart default tag 사용.
     *
     * @return true 면 새로 주입함.
     */
    @SuppressWarnings("unchecked")
    private boolean ensureImageRefs(Map<String, Object> values) {
        Map<String, Object> image = ensureMap(values, "image");
        Object repo = image.get("repository");
        Object tag = image.get("tag");
        boolean repoSet = repo instanceof String s && !s.isBlank();
        boolean tagSet = tag instanceof String s && !s.isBlank();
        if (repoSet || tagSet) {
            return false; // 사용자 의도 존중
        }
        ImageRef parsed = parseImageRef(defaultImageRef());
        image.put("repository", parsed.repository());
        image.put("tag", parsed.tag());
        return true;
    }

    /**
     * docker image reference 를 repository + tag 로 분리.
     * 규칙: 마지막 {@code /} 뒤에서 마지막 {@code :} 를 찾음. 그 앞은 repository,
     * 뒤는 tag. {@code /} 가 없으면 전체에서 마지막 {@code :} 기준.
     * <p>
     * digest ({@code @sha256:...}) 형식은 tag 가 아니므로 tag 가 "latest" 로 떨어지고
     * digest 부분은 repository 에 남는다 — 본 시점에서 sha digest 사용은 흔치 않아 의도적 단순화.
     */
    static ImageRef parseImageRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return new ImageRef("aipaas/cluster-agent", "dev");
        }
        int lastSlash = ref.lastIndexOf('/');
        int lastColon = ref.lastIndexOf(':');
        if (lastColon > lastSlash && lastColon > 0) {
            return new ImageRef(ref.substring(0, lastColon), ref.substring(lastColon + 1));
        }
        // :tag 없음 → docker convention: latest
        return new ImageRef(ref, "latest");
    }

    record ImageRef(String repository, String tag) {}

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ensureMap(Map<String, Object> root, String key) {
        Object node = root.get(key);
        if (node instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        root.put(key, created);
        return created;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYamlToMap(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return new LinkedHashMap<>();
        }
        Object parsed = new Yaml().load(yaml);
        if (parsed == null) {
            return new LinkedHashMap<>();
        }
        if (parsed instanceof Map<?, ?> m) {
            // SnakeYAML 은 보통 LinkedHashMap 을 반환 — 순서 보존.
            return (Map<String, Object>) m;
        }
        // scalar / list root 는 helm values 로 의미 없음 → 무시하고 빈 map.
        return new LinkedHashMap<>();
    }

    private static String dumpYaml(Map<String, Object> values) {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setIndent(2);
        return new Yaml(opts).dump(values);
    }
}
