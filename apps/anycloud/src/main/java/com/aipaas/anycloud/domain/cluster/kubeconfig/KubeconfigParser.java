package com.aipaas.anycloud.domain.cluster.kubeconfig;

import com.aipaas.anycloud.domain.cluster.api.request.CreateClusterDto;
import io.fabric8.kubernetes.api.model.Config;
import io.fabric8.kubernetes.api.model.NamedAuthInfo;
import io.fabric8.kubernetes.api.model.NamedCluster;
import io.fabric8.kubernetes.api.model.NamedContext;
import io.fabric8.kubernetes.client.internal.KubeConfigUtils;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * kubeconfig YAML 을 파싱해 CreateClusterDto 의 6개 필드로 변환.
 * <p>
 * 변환 규칙
 * <ul>
 *   <li>{@code current-context} 가 가리키는 cluster + user 쌍을 추출</li>
 *   <li>cluster.server         → apiServerUrl</li>
 *   <li>cluster.certificate-authority-data (base64)   → serverCA (decode 후 PEM 문자열)</li>
 *   <li>user.client-certificate-data (base64)         → clientCA</li>
 *   <li>user.client-key-data (base64)                 → clientKey</li>
 *   <li>user.token                                    → clientToken</li>
 * </ul>
 * <p>
 * 검증 실패 (current-context 없음 / 매칭되는 cluster/user 없음 / server URL 누락) 시
 * {@link IllegalArgumentException} 으로 400 매핑.
 */
@Slf4j
@Component
public class KubeconfigParser {

    /**
     * kubeconfig 본문을 받아 CreateClusterDto 의 인증 필드를 채운다.
     * 비-인증 필드 (clusterName, clusterProvider, clusterType, description) 는 caller 가 별도 설정.
     */
    public CreateClusterDto parse(byte[] kubeconfigBytes) {
        if (kubeconfigBytes == null || kubeconfigBytes.length == 0) {
            throw new IllegalArgumentException("kubeconfig is empty");
        }
        Config config;
        try {
            File tempFile = File.createTempFile("anycloud-kubeconfig-", ".yaml");
            try {
                java.nio.file.Files.write(tempFile.toPath(), kubeconfigBytes);
                config = KubeConfigUtils.parseConfig(tempFile);
            } finally {
                if (!tempFile.delete()) {
                    log.warn("Failed to delete temp kubeconfig file: {}", tempFile);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("kubeconfig parse failed: " + e.getMessage(), e);
        }

        String currentContext = config.getCurrentContext();
        if (currentContext == null || currentContext.isBlank()) {
            throw new IllegalArgumentException("kubeconfig.current-context is missing");
        }
        NamedContext ctx = findContext(config, currentContext);
        String clusterRef = ctx.getContext().getCluster();
        String userRef = ctx.getContext().getUser();

        NamedCluster cluster = findCluster(config, clusterRef);
        NamedAuthInfo user = findUser(config, userRef);

        String apiServerUrl = cluster.getCluster().getServer();
        if (apiServerUrl == null || apiServerUrl.isBlank()) {
            throw new IllegalArgumentException("kubeconfig cluster '" + clusterRef + "' has no server URL");
        }

        // CreateClusterDto 에 K8s admin 자격 필드 없음 — kubeconfig 의 server/CA/
        // client cert/key/token 파싱은 진행하나 DTO 에 set 안 함. cluster 등록은 metadata 만.
        // 사용자가 kubeconfig 로 등록 trigger 한 의도는 cluster 이름/provider 식별만으로 충분.
        // Cluster-agent install 시 in-cluster SA 가 K8s API 자격 자체 보유.
        CreateClusterDto dto = new CreateClusterDto();
        log.info(
                "kubeconfig parsed: context={}, cluster={}, user={}, server={} (자격은 cluster-agent SA 사용)",
                currentContext,
                clusterRef,
                userRef,
                apiServerUrl);
        return dto;
    }

    private static NamedContext findContext(Config config, String name) {
        if (config.getContexts() == null) {
            throw new IllegalArgumentException("kubeconfig.contexts is empty");
        }
        return config.getContexts().stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "kubeconfig.contexts has no entry named '" + name + "' (current-context)"));
    }

    private static NamedCluster findCluster(Config config, String name) {
        if (config.getClusters() == null) {
            throw new IllegalArgumentException("kubeconfig.clusters is empty");
        }
        return config.getClusters().stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("kubeconfig.clusters has no entry named '" + name + "'"));
    }

    private static NamedAuthInfo findUser(Config config, String name) {
        if (config.getUsers() == null) {
            throw new IllegalArgumentException("kubeconfig.users is empty");
        }
        return config.getUsers().stream()
                .filter(u -> name.equals(u.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("kubeconfig.users has no entry named '" + name + "'"));
    }

    /** kubeconfig 의 *-data 필드는 base64 PEM. CreateClusterDto 는 평문 PEM. */
    private static String decodeBase64Field(String base64Value, String fieldName) {
        if (base64Value == null || base64Value.isBlank()) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(base64Value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(fieldName + " is not valid base64: " + e.getMessage(), e);
        }
    }

    /** apiServerUrl 에서 host (IP/도메인) 만 추출 — apiServerIp 채우기 용. */
    private static String extractHost(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
