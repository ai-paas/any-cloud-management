package com.aipaas.anycloud.domain.agent.bootstrap;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.agent.AgentProperties;
import com.aipaas.anycloud.domain.cluster.model.BootstrapInfo;
import com.aipaas.anycloud.domain.kube.KubeService;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService.IssuedToken;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * backend 가 agent 를 직접 설치하는 {@code API_MANAGED} 모드. 단기 JWT 발급,
 * {@link AgentChartRenderer} 로 manifest 렌더, {@link KubeService#applyResource} 로 적용.
 *
 * <p>호출 전제: ClusterEntity 존재. 없으면 {@code applyResource} 가 cluster 를 찾지 못함.
 *
 * <p>실패 시 예외를 그대로 전파. cluster 를 FAILED 로 전환할지는 호출 측 결정.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentApiManagedInstaller {

    private final AgentBootstrapService bootstrapService;
    private final AgentChartRenderer chartRenderer;
    private final KubeService kubeService;
    private final MeterRegistry meterRegistry;
    private final AgentProperties agentProperties;
    // VM(PULUMI) 프로비저닝 cluster 판별: admin kubeconfig SA 를 그 cluster 에만 chart 로 생성.
    private final com.aipaas.anycloud.domain.cluster.ClusterRepository clusterRepository;

    /**
     * manifest 의 namespace 미지정 resource 에만 적용. 현재 chart 는 모든 resource 에
     * namespace 를 명시하므로 사용되지 않음.
     */
    @Value("${agent.manifest.namespace:aipaas-system}")
    private String agentNamespace;

    /** BootstrapInfo.kubectlApplyCommand 의 curl base URL override. 운영에서 backend 가 reverse-proxy 뒤에 있을 때 외부 접근 가능한 URL 명시 (e.g., https://api.aipaas.example.com). */
    @Value("${anycloud.public-url:}")
    private String configuredPublicUrl;

    /**
     * agent install path 별 counter.
     * Metric: {@code anycloud_agent_install_total{path="AGENT|FAILED"}}.
     */
    private Counter agentPathCounter;

    private Counter failedCounter;

    @PostConstruct
    void initMetrics() {
        this.agentPathCounter = Counter.builder("anycloud.agent.install.total")
                .tag("path", "AGENT")
                .description("Agent install via cluster-agent gRPC routing")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("anycloud.agent.install.total")
                .tag("path", "FAILED")
                .description("Agent install failed (non-AGENT_UNAVAILABLE error)")
                .register(meterRegistry);
    }

    /**
     * 지정 cluster 에 agent 를 설치. install_mode=API_MANAGED 로 token 발급.
     *
     * @param clusterName 대상 cluster (ClusterEntity.id)
     * @return 발급된 token 정보 (호출 측이 결과 로깅 / DB 보관)
     */
    @com.aipaas.anycloud.domain.audit.Audited(
            action = "agent.install",
            resourceType = "clusterAgent",
            resourceId = "#clusterName",
            summary = "'jti=' + #result?.registrationJti() + ', expires=' + #result?.tokenExpiresAt() "
                    + "+ ', manifestBytes=' + #result?.manifestBytes()")
    public AgentInstallResult install(String clusterName) {
        log.info("Installing cluster agent for cluster_id={} (API_MANAGED mode)", clusterName);

        IssuedToken issued = bootstrapService.issueRegistrationToken(clusterName, "API_MANAGED");
        String manifest = chartRenderer.render(issued.token(), adminKubeconfigEnabled(clusterName));

        try {
            kubeService.applyResource(clusterName, agentNamespace, manifest);
            agentPathCounter.increment();
            log.info(
                    "Cluster agent manifest applied via AGENT path cluster_id={} token_expires_at={} manifest_bytes={}",
                    clusterName,
                    issued.expiresAt(),
                    manifest.length());
        } catch (CustomException e) {
            failedCounter.increment();
            if (e.getErrorCode() == ErrorCode.AGENT_UNAVAILABLE) {
                throw new CustomException(manualInstallGuide(clusterName), ErrorCode.AGENT_UNAVAILABLE);
            }
            throw e;
        }

        return new AgentInstallResult(
                clusterName, issued.jti(), issued.expiresAt().toString(), manifest.length());
    }

    /**
     * agent gRPC 가 없을 때의 복구 안내.
     *
     * <p>backend 는 cluster 자격증명을 보관하지 않으므로 agent 를 거치지 않는 apply 경로가 없음.
     * 운영자가 자신의 kubectl context 에서 실행할 명령을 반환.
     *
     * <p>registration token 미포함. 이 문자열은 에러 응답과 로그에 남고 token 은 그대로 등록에
     * 사용 가능한 자격증명. manifest endpoint 가 호출마다 새로 발급.
     */
    private String manualInstallGuide(String clusterName) {
        return "Cluster agent 가 응답하지 않습니다. backend 는 cluster 자격증명을 보관하지 않아 대신 적용할 수 "
                + "없습니다. 직접 설치하세요: curl -sS " + resolveBackendBaseUrl() + "/v1/clusters/" + clusterName
                + "/agent-manifest.yaml | kubectl apply -f -";
    }

    public record AgentInstallResult(
            String clusterId, String registrationJti, String tokenExpiresAt, int manifestBytes) {}

    /** GET /v1/clusters/{id}/agent-manifest.yaml 가 호출하는 helper. */
    public String renderManifest(String clusterName, String registrationToken) {
        String chart = chartRenderer.render(registrationToken, adminKubeconfigEnabled(clusterName));
        String namespaceYaml = ""
                + "---\n"
                + "apiVersion: v1\n"
                + "kind: Namespace\n"
                + "metadata:\n"
                + "  name: " + agentNamespace + "\n"
                + "  labels:\n"
                + "    app.kubernetes.io/managed-by: anycloud-backend\n"
                + "    app.kubernetes.io/component: cluster-agent\n";
        return namespaceYaml + chart;
    }

    /**
     * VM(PULUMI) 프로비저닝 cluster 여부: 전체 권한 admin SA(aipaas-admin)를 chart 에 포함할지 결정.
     * registered/IMPORTED cluster 는 false (사용자 자격 사용, cluster-admin SA 미생성).
     * cluster 조회 실패 시 보수적으로 false.
     */
    private boolean adminKubeconfigEnabled(String clusterName) {
        return clusterRepository
                .findById(clusterName)
                .map(com.aipaas.anycloud.domain.cluster.ClusterEntity::isVmProvisioned)
                .orElse(false);
    }

    /**
     * Agent-led registration 의 핵심: token 만 발급하고 manifest apply 는 사용자에게 위임.
     * {@link #install} 의 JWT 발급 부분만 떼어 helm install / kubectl apply 명령 문자열을 함께 만든다.
     *
     * @param clusterName 대상 cluster (ClusterEntity.id)
     * @return Bootstrap 정보: POST /v1/clusters 응답에 포함되어 사용자가 즉시 install 실행 가능.
     */
    @com.aipaas.anycloud.domain.audit.Audited(
            action = "agent.bootstrap.prepare",
            resourceType = "clusterAgent",
            resourceId = "#clusterName",
            summary = "'expires=' + #result?.expiresAt()")
    public BootstrapInfo prepareBootstrap(String clusterName) {
        IssuedToken issued = bootstrapService.issueRegistrationToken(clusterName, "USER_MANAGED");
        String backendEndpoint = agentProperties.grpc().publicEndpoint();
        // docker hub / OCI image registry 의 helm chart: backend image 와 동일 패턴.
        // 사용자가 BACKEND_DOCKER_USER / BACKEND_IMAGE_TAG override 한 경우 chart 도 같은 namespace.
        String helmInstallCommand = String.format(
                "helm install cluster-agent \\\n" + "  --namespace aipaas-system --create-namespace \\\n"
                        + "  --set bootstrap.registrationToken=%s \\\n"
                        + "  --set backend.grpcAddr=%s \\\n"
                        + "  oci://docker.io/aipaas/cluster-agent --version 0.1.0",
                issued.token(), backendEndpoint);
        String manifestUrl = "/v1/clusters/" + clusterName + "/agent-manifest.yaml";
        String kubectlApplyCommand = "curl -sS " + resolveBackendBaseUrl() + manifestUrl + " | kubectl apply -f -";
        return new BootstrapInfo(
                issued.token(),
                issued.expiresAt().toString(),
                backendEndpoint,
                manifestUrl,
                helmInstallCommand,
                kubectlApplyCommand);
    }

    /**
     * kubectl apply 명령의 curl base URL 도출. 우선순위:
     * <ol>
     *   <li>{@code anycloud.public-url} property: 운영에서 reverse-proxy / gateway 경유 URL 명시</li>
     *   <li>현재 HTTP 요청의 scheme://host[:port]. dev / single-host 자동 해결 (X-Forwarded-* 헤더가
     *       설정되어 있으면 framework 가 자동 반영: server.forward-headers-strategy=FRAMEWORK 권장)</li>
     *   <li>localhost:8888: request context 외부 호출 (테스트 등) fallback</li>
     * </ol>
     */
    private String resolveBackendBaseUrl() {
        if (configuredPublicUrl != null && !configuredPublicUrl.isBlank()) {
            return configuredPublicUrl.endsWith("/")
                    ? configuredPublicUrl.substring(0, configuredPublicUrl.length() - 1)
                    : configuredPublicUrl;
        }
        try {
            return org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromCurrentContextPath()
                    .build()
                    .toUriString();
        } catch (IllegalStateException e) {
            // request context 외부 (e.g., 비동기 worker). fallback default
            return "http://localhost:8888";
        }
    }
}
