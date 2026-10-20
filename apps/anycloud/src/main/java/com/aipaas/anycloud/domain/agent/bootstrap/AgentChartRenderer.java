package com.aipaas.anycloud.domain.agent.bootstrap;

import com.aipaas.anycloud.domain.chart.internal.HelmCommandExecutor;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Cluster Agent 의 Kubernetes manifest 를 단일 source-of-truth (Helm chart) 로부터 동적 생성.
 *
 * <p>chart 는 {@code classpath:agent-chart/} 에 있습니다. Gradle 의
 * {@code copyAgentChartResources} 가 빌드 때 {@code apps/agent/deploy/helm/cluster-agent/} 를
 * 그대로 복사해 jar 에 넣습니다. chartmuseum 없이도 설치할 수 있게 하려는 것입니다.
 *
 * <p>부팅 때 chart 를 임시 디렉토리로 한 번 풀고 Chart.yaml 이 있는지 봅니다. 없으면 부팅이
 * 실패합니다. 설치를 시도하는 시점이 아니라 지금 알아야 하기 때문입니다.
 *
 * <p>렌더링은 {@code helm template} 에 맡깁니다. manifest 를 Java 문자열로 조립하면 chart 와
 * 두 벌이 되어 RBAC 같은 변경을 양쪽에 따로 반영해야 합니다.
 */
@Slf4j
@Component
public class AgentChartRenderer {

    private static final String CLASSPATH_PATTERN = "classpath:agent-chart/**";
    private static final String CHART_PREFIX = "agent-chart/";
    private static final String RELEASE_NAME = "cluster-agent";

    private final HelmCommandExecutor helm;

    @Value("${agent.grpc.public-endpoint:${ANYCLOUD_AGENT_GRPC_PUBLIC_ENDPOINT:host.docker.internal:9090}}")
    private String backendEndpoint;

    @Value("${agent.manifest.image:${ANYCLOUD_AGENT_IMAGE:ghcr.io/ai-paas/cluster-agent:latest}}")
    private String agentImage;

    @Value("${agent.manifest.namespace:aipaas-system}")
    private String agentNamespace;

    @Value("${agent.manifest.image-pull-policy:IfNotPresent}")
    private String imagePullPolicy;

    /** 부팅 시 classpath chart 를 /tmp 로 추출한 디렉토리. lazy init 도 가능하지만 부팅 시 검증 우선. */
    private Path chartDir;

    public AgentChartRenderer(HelmCommandExecutor helm) {
        this.helm = helm;
    }

    @PostConstruct
    void extractChart() throws IOException {
        Path tmpRoot = Files.createTempDirectory("anycloud-agent-chart-");
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(CLASSPATH_PATTERN);

        int copied = 0;
        for (Resource r : resources) {
            if (!r.isReadable()) {
                continue; // directory entry
            }
            String relative = relativePath(r);
            if (relative.isEmpty() || relative.endsWith("/")) {
                continue;
            }
            Path dest = tmpRoot.resolve(relative);
            Files.createDirectories(dest.getParent());
            try (InputStream in = r.getInputStream()) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }

        Path chartYaml = tmpRoot.resolve("Chart.yaml");
        if (!Files.isRegularFile(chartYaml)) {
            throw new IllegalStateException("agent-chart bundle missing — Chart.yaml not found at " + chartYaml
                    + ". Did Gradle copyAgentChartResources run? Check apps/anycloud/build.gradle.");
        }
        this.chartDir = tmpRoot;
        log.info("AgentChartRenderer: extracted cluster-agent chart to {} ({} files)", tmpRoot, copied);
    }

    /**
     * 지정 token 으로 manifest 를 렌더. caller (AgentApiManagedInstaller) 가 server-side apply.
     *
     * @param registrationToken bootstrap 단기 JWT
     * @return multi-doc YAML manifest
     */
    public String render(String registrationToken) {
        return render(registrationToken, false);
    }

    /**
     * @param adminKubeconfig true 면 chart 가 cluster-admin SA(aipaas-admin)를 추가 생성
     *        ({@code rbac.adminKubeconfig.enabled}). VM(PULUMI) 프로비저닝 cluster 의 전체 권한
     *        kubeconfig 다운로드용. registered/BYO 는 false — 사용자 자격 사용.
     */
    public String render(String registrationToken, boolean adminKubeconfig) {
        if (registrationToken == null || registrationToken.isBlank()) {
            throw new IllegalArgumentException("registrationToken required");
        }
        Path valuesFile = null;
        try {
            valuesFile = writeValuesYaml(registrationToken, adminKubeconfig);
            return helm.executeAndCheck(helm.templateArgs(RELEASE_NAME, chartDir, agentNamespace, valuesFile), null);
        } finally {
            if (valuesFile != null) {
                try {
                    Files.deleteIfExists(valuesFile);
                } catch (IOException e) {
                    log.warn("Failed to delete temp values file {}: {}", valuesFile, e.toString());
                }
            }
        }
    }

    /**
     * Values.yaml 생성 — chart 의 values.yaml 위에 override 되는 key 만 들고 있음.
     * 사용자가 helm install 시 명시 안 한 항목들 (token / endpoint / image) 만 주입.
     */
    private Path writeValuesYaml(String token, boolean adminKubeconfig) {
        ImageRef img = parseImageRef(agentImage);
        String yaml = String.format(
                """
				backend:
				  grpcAddr: "%s"
				bootstrap:
				  registrationToken: "%s"
				image:
				  repository: "%s"
				  tag: "%s"
				  pullPolicy: "%s"
				agent:
				  namespace: "%s"
				rbac:
				  adminKubeconfig:
				    enabled: %s
				""",
                backendEndpoint, token, img.repository(), img.tag(), imagePullPolicy, agentNamespace, adminKubeconfig);
        try {
            Path f = Files.createTempFile("anycloud-agent-values-", ".yaml");
            Files.writeString(f, yaml);
            return f;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write agent values.yaml: " + e.getMessage(), e);
        }
    }

    /** Resource 의 jar URL ({@code jar:file:/app/app.jar!/agent-chart/templates/deployment.yaml}) 또는 file URL ({@code file:/.../agent-chart/templates/deployment.yaml}) 에서 "agent-chart/" 뒤의 relative path 만 추출. */
    private static String relativePath(Resource r) throws IOException {
        String url = r.getURL().toString();
        int idx = url.lastIndexOf(CHART_PREFIX);
        if (idx < 0) {
            throw new IllegalStateException("Resource URL does not contain '" + CHART_PREFIX + "': " + url);
        }
        return url.substring(idx + CHART_PREFIX.length());
    }

    /**
     * "<repo>:<tag>" 분리. {@code registry.local:5000/cluster-agent:dev} 형태 안전 처리
     * (마지막 {@code /} 뒤의 마지막 {@code :} 만 split).
     */
    static ImageRef parseImageRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return new ImageRef("ghcr.io/ai-paas/cluster-agent", "latest");
        }
        int lastSlash = ref.lastIndexOf('/');
        int lastColon = ref.lastIndexOf(':');
        if (lastColon > lastSlash && lastColon > 0) {
            return new ImageRef(ref.substring(0, lastColon), ref.substring(lastColon + 1));
        }
        return new ImageRef(ref, "latest");
    }

    record ImageRef(String repository, String tag) {}

    /** test 보조 — chart 추출 위치 확인. */
    Path getChartDir() {
        return chartDir;
    }
}
