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
 * Cluster Agent 의 Kubernetes manifest 를 단일 출처 (Helm chart) 로부터 동적 생성.
 *
 * <p>chart 위치는 {@code classpath:agent-chart/}. Gradle 의 {@code copyAgentChartResources} 가
 * 빌드 시 {@code apps/agent/deploy/helm/cluster-agent/} 를 복사해 jar 에 포함. chartmuseum 접근이
 * 불가한 환경에서도 설치 가능.
 *
 * <p>부팅 시 chart 를 임시 디렉터리에 1회 전개하고 Chart.yaml 존재 확인. 부재 시 부팅 실패.
 * 설치 시점이 아닌 부팅 시점에 검출 목적.
 *
 * <p>렌더링은 {@code helm template} 위임. manifest 를 Java 문자열로 조립하면 chart 와 이중화돼
 * RBAC 등의 변경을 양쪽에 각각 반영 필요.
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
     *        kubeconfig 다운로드용. registered/BYO 는 false: 사용자 자격 사용.
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
     * Values.yaml 생성: chart 의 values.yaml 위에 override 되는 key 만 들고 있음.
     * 사용자가 helm install 시 명시 안 한 항목들 (token / endpoint / image) 만 주입.
     *
     * <p>{@code identity.cleanupHook} 은 항상 off. 본 렌더 결과의 적용 주체는 helm 이 아닌
     * kubectl 이고, kubectl 은 {@code helm.sh/hook} annotation 미인식. cleanup Job 이 일반 Job
     * 으로 생성돼 Deployment 와 순서 없이 경쟁하므로 agent 가 저장한 cluster-agent-identity
     * Secret 삭제 가능. 그 상태의 재시작은 registration token (10분 TTL) 만료로 CrashLoop 진입.
     * cleanup 의 대상인 stale identity 는 agent 의 연속 Unauthenticated 3회 자가 삭제로 복구
     * (apps/agent/internal/core/runtime.go). hook 순서가 보장되는 helm 경로는 chart default
     * (enabled=true) 유지.
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
				identity:
				  cleanupHook:
				    enabled: false
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

    /** test 보조: chart 추출 위치 확인. */
    Path getChartDir() {
        return chartDir;
    }
}
