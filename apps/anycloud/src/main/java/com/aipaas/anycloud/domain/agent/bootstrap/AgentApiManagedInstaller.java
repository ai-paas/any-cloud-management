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
 * Pulumi-provisioned (또는 사용자가 직접 kubeconfig 업로드 한) cluster 에 agent 를 backend 가
 * 직접 설치하는 PDF 의 {@code API_MANAGED} 모드 구현.
 *
 * <p>호출 시점: BOOTSTRAP step 의 {@code registerFromKubeconfig} 직후 — ClusterEntity 가
 * 만들어진 후라 {@link KubeService#applyResource} 가 사용 가능.
 *
 * <p>흐름:
 * <ol>
 *   <li>{@link AgentBootstrapService#issueRegistrationToken} 로 단기 JWT 발급</li>
 *   <li>{@link AgentManifestRenderer#render} 로 manifest YAML 생성 (token + backend 주입)</li>
 *   <li>{@link KubeService#applyResource} 로 cluster 에 apply (fabric8 server-side)</li>
 * </ol>
 *
 * <p>실패 시 본 service 는 예외 propagate — BOOTSTRAP step 이 catch 해 cluster 를 FAILED
 * 로 마크하거나 graceful 한 path 로 continue (호출 측 결정).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentApiManagedInstaller {

    private final AgentBootstrapService bootstrapService;
    // chart 가 단일 source-of-truth — Java String 인라인 template (AgentManifestRenderer) 대체.
    private final AgentChartRenderer chartRenderer;
    private final KubeService kubeService;
    private final MeterRegistry meterRegistry;
    private final AgentProperties agentProperties;
    // VM(PULUMI) 프로비저닝 cluster 판별 — admin kubeconfig SA 를 그 cluster 에만 chart 로 생성.
    private final com.aipaas.anycloud.domain.cluster.ClusterRepository clusterRepository;

    /**
     * agent.manifest.namespace — manifest sub-resource 들의 default namespace.
     * 본 chart 는 모든 resource 의 namespace 가 명시되어 있어 사실상 fallback 용.
     */
    @Value("${agent.manifest.namespace:aipaas-system}")
    private String agentNamespace;

    /**
     * agent install path 별 counter — chicken-and-egg fallback (BOOTSTRAP) 발동 빈도 추적.
     * Metric: {@code anycloud_agent_install_total{path="AGENT|BOOTSTRAP|FAILED"}}.
     */
    private Counter agentPathCounter;

    private Counter bootstrapPathCounter;
    private Counter failedCounter;

    @PostConstruct
    void initMetrics() {
        this.agentPathCounter = Counter.builder("anycloud.agent.install.total")
                .tag("path", "AGENT")
                .description("Agent install via cluster-agent gRPC routing")
                .register(meterRegistry);
        this.bootstrapPathCounter = Counter.builder("anycloud.agent.install.total")
                .tag("path", "BOOTSTRAP")
                .description("Agent install via fabric8 bootstrap (chicken-and-egg fallback)")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("anycloud.agent.install.total")
                .tag("path", "FAILED")
                .description("Agent install failed (non-AGENT_UNAVAILABLE error)")
                .register(meterRegistry);
    }

    /**
     * 지정 cluster 에 agent 를 설치. install_mode=API_MANAGED 로 token 발급.
     *
     * <p>{@code @Audited} 추가. cluster 최초 등록 (auto) 과 admin reinstall
     * endpoint (manual) 양쪽에서 호출되므로 audit_log 에 install attempt 추적.
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

        // First-install chicken-and-egg: agent 가 아직 cluster 안에 없으므로 agent gRPC routing 은
        // "no active session" 으로 실패. 이 경우 ClusterEntity 의 kubeconfig 로 backend 가 직접 apply
        // ({@link KubeService#applyResourceViaBootstrap}). 이후 agent 가 떠서 backend 에 connect 하면
        // 다음부터의 day-2 ops 는 정상적으로 agent gRPC 통해 진행.
        try {
            kubeService.applyResource(clusterName, agentNamespace, manifest);
            agentPathCounter.increment();
            log.info(
                    "Cluster agent manifest applied via AGENT path cluster_id={} token_expires_at={} manifest_bytes={}",
                    clusterName,
                    issued.expiresAt(),
                    manifest.length());
        } catch (CustomException e) {
            if (e.getErrorCode() == ErrorCode.AGENT_UNAVAILABLE) {
                log.info(
                        "Agent gRPC not yet available for cluster_id={} — falling back to BOOTSTRAP fabric8 path",
                        clusterName);
                try {
                    kubeService.applyResourceViaBootstrap(clusterName, agentNamespace, manifest);
                    bootstrapPathCounter.increment();
                    log.info(
                            "Cluster agent manifest applied via BOOTSTRAP path cluster_id={} token_expires_at={} manifest_bytes={}",
                            clusterName,
                            issued.expiresAt(),
                            manifest.length());
                } catch (RuntimeException bootstrapFail) {
                    failedCounter.increment();
                    throw bootstrapFail;
                }
            } else {
                failedCounter.increment();
                throw e;
            }
        }

        return new AgentInstallResult(
                clusterName, issued.jti(), issued.expiresAt().toString(), manifest.length());
    }

    public record AgentInstallResult(
            String clusterId, String registrationJti, String tokenExpiresAt, int manifestBytes) {}

    /**
     * Agent-led registration 의 핵심 — token 만 발급하고 manifest apply 는
     * 사용자에게 위임. {@link #install} 의 (1) JWT 발급 부분만 분리 + helm install / kubectl apply
     * 명령 문자열 빌드.
     *
     * <p>backend 가 cluster 의 K8s API 를 직접 치지 않으므로 fabric8 path 의존성 없음 — apiServerUrl /
     * CA / clientKey 자격이 없어도 동작. install 책임은 사용자에게.
     *
     * @param clusterName 대상 cluster (ClusterEntity.id)
     * @return Bootstrap 정보 — POST /v1/clusters 응답에 포함되어 사용자가 즉시 install 실행 가능.
     */
    @com.aipaas.anycloud.domain.audit.Audited(
            action = "agent.bootstrap.prepare",
            resourceType = "clusterAgent",
            resourceId = "#clusterName",
            summary = "'expires=' + #result?.expiresAt()")
    /**
     * GET /v1/clusters/{id}/agent-manifest.yaml 가 호출하는 helper.
     * 이미 발급된 token 으로 helm chart 를 렌더해 raw YAML 반환.
     *
     * <p> fix: helm chart 는 namespace 를 만들지 않음 (helm 의 --create-namespace 는
     * install command 옵션). {@code kubectl apply -f -} path 에서 manifest 가 self-contained 여야
     * 하므로 Namespace resource 를 manifest 앞에 prepend.
     */
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
     * VM(PULUMI) 프로비저닝 cluster 여부 — 전체 권한 admin SA(aipaas-admin)를 chart 에 포함할지 결정.
     * registered/IMPORTED cluster 는 false (사용자 자격 사용, cluster-admin SA 미생성).
     * cluster 조회 실패 시 보수적으로 false.
     */
    private boolean adminKubeconfigEnabled(String clusterName) {
        return clusterRepository
                .findById(clusterName)
                .map(com.aipaas.anycloud.domain.cluster.ClusterEntity::isVmProvisioned)
                .orElse(false);
    }

    public BootstrapInfo prepareBootstrap(String clusterName) {
        IssuedToken issued = bootstrapService.issueRegistrationToken(clusterName, "USER_MANAGED");
        String backendEndpoint = agentProperties.grpc().publicEndpoint();
        // docker hub / OCI image registry 의 helm chart — backend image 와 동일 패턴.
        // 사용자가 BACKEND_DOCKER_USER / BACKEND_IMAGE_TAG override 한 경우 chart 도 같은 namespace.
        String helmInstallCommand = String.format(
                "helm install cluster-agent \\\n" + "  --namespace aipaas-system --create-namespace \\\n"
                        + "  --set bootstrap.registrationToken=%s \\\n"
                        + "  --set backend.grpcAddr=%s \\\n"
                        + "  oci://docker.io/aipaas/cluster-agent --version 0.1.0",
                issued.token(), backendEndpoint);
        String manifestUrl = "/v1/clusters/" + clusterName + "/agent-manifest.yaml";
        String kubectlApplyCommand = "curl -sS http://<anycloud-backend>:8888" + manifestUrl + " | kubectl apply -f -";
        return new BootstrapInfo(
                issued.token(),
                issued.expiresAt().toString(),
                backendEndpoint,
                manifestUrl,
                helmInstallCommand,
                kubectlApplyCommand);
    }
}
