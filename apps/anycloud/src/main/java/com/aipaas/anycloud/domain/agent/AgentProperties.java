package com.aipaas.anycloud.domain.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code agent.yaml} 의 {@code agent.*} 설정입니다. 중첩 record 가 yaml 구조와 그대로 대응합니다.
 *
 * <p>개별 값을 {@code @Value} 로 흩어 주입하지 말고 이 record 를 주입해
 * {@code properties.helm().repoAlias()} 처럼 쓰세요.
 *
 * <p>환경변수 기본값은 yaml 의 {@code ${ENV:default}} 로 둡니다. 여기서 처리하지 않습니다.
 */
@ConfigurationProperties("agent")
public record AgentProperties(Grpc grpc, Manifest manifest, Helm helm, ApiManaged apiManaged) {

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
            /** dev 편의: production 절대 사용 금지. */
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
            if (image == null || image.isBlank()) image = "ghcr.io/ai-paas/cluster-agent:latest";
            if (namespace == null || namespace.isBlank()) namespace = "aipaas-system";
            if (imagePullPolicy == null || imagePullPolicy.isBlank()) imagePullPolicy = "IfNotPresent";
            if (deploymentName == null || deploymentName.isBlank()) deploymentName = "cluster-agent";
            if (containerName == null || containerName.isBlank()) containerName = "agent";
        }
    }

    public record Helm(
            /** helm repo add 의 alias. */
            String repoAlias,
            /** helm chart repository URL. 빈 값 = 미설정: 자동 install 에는 영향 없음. */
            String repoUrl,
            String chartName,
            String chartVersion) {
        public Helm {
            if (repoAlias == null || repoAlias.isBlank()) repoAlias = "anycloud";
            if (repoUrl == null) repoUrl = "";
            if (chartName == null || chartName.isBlank()) chartName = "cluster-agent";
            if (chartVersion == null || chartVersion.isBlank()) chartVersion = "0.1.0";
        }
    }

    public record ApiManaged(
            /** Pulumi-provisioned cluster 에 backend 가 자동 agent 설치 여부. */
            boolean enabled) {}

    // Policy record 제거. ConfigMap 이 단일 출처. backend defaults 없음.

    // constructor가 nested null 경우 fallback default 채워주도록.
    public AgentProperties {
        if (grpc == null) grpc = new Grpc(null, null, null);
        if (manifest == null) manifest = new Manifest(null, null, null, null, null);
        if (helm == null) helm = new Helm(null, null, null, null);
        if (apiManaged == null) apiManaged = new ApiManaged(true);
    }
}
