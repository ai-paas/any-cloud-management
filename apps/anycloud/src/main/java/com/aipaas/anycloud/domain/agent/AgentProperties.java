package com.aipaas.anycloud.domain.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * anycloud 호스트의 agent.* 설정 통합 — type-safe 단일 진입점.
 *
 * <p>각 caller 는 {@code AgentProperties properties} 만 inject 하고
 * {@code properties.helm().repoAlias()} 형식 접근.
 *
 * <p>{@code agent.yaml} 의 namespace 와 1:1 매칭. ENV var 의존 default 는 yaml 의
 * {@code ${ENV:default}} 표현으로 처리 (Spring Boot 가 자동 resolve).
 *
 * <p>매핑 대상:
 * <ul>
 *   <li>{@code agent.grpc.public-endpoint, port, tls.*}</li>
 *   <li>{@code agent.manifest.image, namespace, image-pull-policy}</li>
 *   <li>{@code agent.helm.repo-alias, repo-url, chart-name, chart-version}</li>
 *   <li>{@code agent.api-managed.enabled}</li>
 *   <li>{@code agent.kubeconfig.cleanup-on-active}</li>
 * </ul>
 *
 * <p>등록은 {@code AgentConfiguration} 의 {@code @EnableConfigurationProperties} 가 처리.
 */
@ConfigurationProperties("agent")
public record AgentProperties(Grpc grpc, Manifest manifest, Helm helm, ApiManaged apiManaged, Kubeconfig kubeconfig) {

    public record Grpc(
            Integer port,
            /** Agent → backend 가 outbound 로 접속할 public endpoint. */
            String publicEndpoint,
            Tls tls) {
        public Grpc {
            if (port == null) port = 9090;
            if (publicEndpoint == null || publicEndpoint.isBlank()) publicEndpoint = "localhost:9090";
            if (tls == null) tls = new Tls(false, "", "", false);
        }
    }

    public record Tls(
            boolean enabled,
            /** Backend 의 server cert 검증용 CA cert (PEM). 비우면 system roots 시도. */
            String caCert,
            /** SNI override. 비우면 public-endpoint 의 host 부분 자동 사용. */
            String serverName,
            /** dev 편의 — production 절대 사용 금지. */
            boolean insecureSkipVerify) {
        public Tls {
            if (caCert == null) caCert = "";
            if (serverName == null) serverName = "";
        }
    }

    public record Manifest(
            /** Agent container image (chart values.image override). */
            String image,
            /** Agent 가 설치될 K8s namespace. */
            String namespace,
            String imagePullPolicy,
            /** Fleet upgrade 시 agent deployment 이름 (helm release name). */
            String deploymentName,
            /** Fleet upgrade 시 patch 대상 container 이름. */
            String containerName) {
        public Manifest {
            if (image == null || image.isBlank()) image = "aipaas/cluster-agent:dev";
            if (namespace == null || namespace.isBlank()) namespace = "aipaas-system";
            if (imagePullPolicy == null || imagePullPolicy.isBlank()) imagePullPolicy = "IfNotPresent";
            if (deploymentName == null || deploymentName.isBlank()) deploymentName = "cluster-agent";
            if (containerName == null || containerName.isBlank()) containerName = "agent";
        }
    }

    public record Helm(
            /** helm repo add 의 alias. */
            String repoAlias,
            /** helm chart repository URL. */
            String repoUrl,
            String chartName,
            String chartVersion) {
        public Helm {
            if (repoAlias == null || repoAlias.isBlank()) repoAlias = "anycloud";
            if (repoUrl == null || repoUrl.isBlank()) repoUrl = "http://chartmuseum:8080";
            if (chartName == null || chartName.isBlank()) chartName = "cluster-agent";
            if (chartVersion == null || chartVersion.isBlank()) chartVersion = "0.1.0";
        }
    }

    public record ApiManaged(
            /** Pulumi-provisioned cluster 에 backend 가 자동 agent 설치 여부. */
            boolean enabled) {}

    public record Kubeconfig(
            /** Agent ACTIVE 시 ClusterEntity 의 kubeconfig 필드 (server_ca/client_ca/client_key/client_token) 비동기 NULL. */
            boolean cleanupOnActive) {}

    // Policy record 제거. ConfigMap 이 단일 source. backend defaults 없음.

    // constructor가 nested null 경우 fallback default 채워주도록.
    public AgentProperties {
        if (grpc == null) grpc = new Grpc(null, null, null);
        if (manifest == null) manifest = new Manifest(null, null, null, null, null);
        if (helm == null) helm = new Helm(null, null, null, null);
        if (apiManaged == null) apiManaged = new ApiManaged(true);
        if (kubeconfig == null) kubeconfig = new Kubeconfig(false);
    }
}
