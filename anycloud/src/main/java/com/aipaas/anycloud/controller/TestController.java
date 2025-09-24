package com.aipaas.anycloud.controller;

import com.aipaas.anycloud.configuration.bean.KubernetesClientConfig;
import com.aipaas.anycloud.model.entity.ClusterEntity;
import com.aipaas.anycloud.repository.ClusterRepository;
import io.fabric8.kubernetes.api.model.NamespaceList;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/test")
@RequiredArgsConstructor
public class TestController {

    private final ClusterRepository clusterRepository;

    @PostMapping("/kubeconfig-client")
    public ResponseEntity<?> testKubeconfigClient(@RequestParam String clusterId) {
        log.info("🚀 Testing kubeconfig-based KubernetesClient for cluster: {}", clusterId);

        Map<String, Object> result = new HashMap<>();
        result.put("clusterId", clusterId);

        try {
            // 🔍 DB에서 클러스터 정보 조회
            ClusterEntity cluster = clusterRepository.findById(clusterId)
                    .orElseThrow(() -> new RuntimeException("Cluster not found: " + clusterId));

            result.put("apiServerUrl", cluster.getApiServerUrl());
            result.put("clusterProvider", cluster.getClusterProvider());

            log.info("🔍 DB cluster details:");
            log.info("  - ID: {}", cluster.getId());
            log.info("  - API URL: {}", cluster.getApiServerUrl());
            log.info("  - Provider: {}", cluster.getClusterProvider());
            log.info("  - Has Token: {}", cluster.getClientToken() != null && !cluster.getClientToken().trim().isEmpty());
            log.info("  - Has ClientCa: {}", cluster.getClientCa() != null && !cluster.getClientCa().trim().isEmpty());
            log.info("  - Has ClientKey: {}", cluster.getClientKey() != null && !cluster.getClientKey().trim().isEmpty());
            log.info("  - Has ServerCa: {}", cluster.getServerCa() != null && !cluster.getServerCa().trim().isEmpty());
            
            
            KubernetesClientConfig kubernetesClientConfig = new KubernetesClientConfig(cluster);
            KubernetesClient client = kubernetesClientConfig.getClient();
            // // 🔍 인증서 데이터 상세 분석
            // if (cluster.getClientCa() != null) {
            //     String certSample = cluster.getClientCa().length() > 100 ?
            //             cluster.getClientCa().substring(0, 100) + "..." : cluster.getClientCa();
            //     log.info("  - ClientCa sample: {}", certSample);
            //     log.info("  - ClientCa length: {}", cluster.getClientCa().length());

            //     // Base64 디코딩 테스트
            //     try {
            //         byte[] decoded = java.util.Base64.getDecoder().decode(cluster.getClientCa().trim());
            //         String certContent = new String(decoded);
            //         boolean isPem = certContent.contains("-----BEGIN CERTIFICATE-----");
            //         log.info("  - ClientCa format: {} (decoded length: {})", isPem ? "PEM" : "DER", decoded.length);
            //         if (!isPem) {
            //             log.info("  - ClientCa DER content preview: {}", certContent.substring(0, Math.min(50, certContent.length())));
            //         }
            //     } catch (Exception e) {
            //         log.warn("  - ClientCa decode failed: {}", e.getMessage());
            //     }
            // }

            // if (cluster.getClientKey() != null) {
            //     String keySample = cluster.getClientKey().length() > 100 ?
            //             cluster.getClientKey().substring(0, 100) + "..." : cluster.getClientKey();
            //     log.info("  - ClientKey sample: {}", keySample);
            //     log.info("  - ClientKey length: {}", cluster.getClientKey().length());

            //     // Base64 디코딩 테스트
            //     try {
            //         byte[] decoded = java.util.Base64.getDecoder().decode(cluster.getClientKey().trim());
            //         String keyContent = new String(decoded);
            //         boolean isPem = keyContent.contains("-----BEGIN");
            //         log.info("  - ClientKey format: {} (decoded length: {})", isPem ? "PEM" : "DER", decoded.length);
            //         if (!isPem) {
            //             log.info("  - ClientKey DER content preview: {}", keyContent.substring(0, Math.min(50, keyContent.length())));
            //         }
            //     } catch (Exception e) {
            //         log.warn("  - ClientKey decode failed: {}", e.getMessage());
            //     }
            // }

           
          
            // // 🚀 KubernetesClient 생성

            // KubernetesClient client = new KubernetesClientBuilder()
            //         .withConfig(config)
            //         .build();

            // 🔍 클러스터 연결 테스트
            String version = client.getKubernetesVersion().getGitVersion();
            log.info("✅ Successfully connected to cluster. Version: {}", version);

            // 🔍 리소스 조회 테스트
            var resourceTests = new HashMap<String, Object>();

            // 1. 네임스페이스 조회 테스트
            try {
                NamespaceList namespaces = client.namespaces().list();
                resourceTests.put("namespaces", Map.of(
                        "success", true,
                        "count", namespaces.getItems().size(),
                        "names", namespaces.getItems().stream()
                                .map(ns -> ns.getMetadata().getName())
                                .limit(5)
                                .toList()
                ));
                log.info("📁 Found {} namespaces", namespaces.getItems().size());
            } catch (Exception e) {
                resourceTests.put("namespaces", Map.of(
                        "success", false,
                        "error", e.getMessage()
                ));
                log.error("❌ Failed to list namespaces: {}", e.getMessage());
            }

            // 2. kube-system 네임스페이스 Pod 조회 테스트
            try {
                PodList kubeSystemPods = client.pods().inNamespace("kube-system").list();
                resourceTests.put("kube-system-pods", Map.of(
                        "success", true,
                        "podCount", kubeSystemPods.getItems().size(),
                        "namespace", "kube-system"
                ));
                log.info("📦 Found {} pods in kube-system", kubeSystemPods.getItems().size());
            } catch (Exception e) {
                resourceTests.put("kube-system-pods", Map.of(
                        "success", false,
                        "error", e.getMessage(),
                        "namespace", "kube-system"
                ));
                log.error("❌ Failed to list pods in kube-system: {}", e.getMessage());
            }

            // 3. default 네임스페이스 Pod 조회 테스트
            try {
                PodList defaultPods = client.pods().inNamespace("default").list();
                resourceTests.put("default-pods", Map.of(
                        "success", true,
                        "podCount", defaultPods.getItems().size(),
                        "namespace", "default"
                ));
                log.info("📦 Found {} pods in default", defaultPods.getItems().size());
            } catch (Exception e) {
                resourceTests.put("default-pods", Map.of(
                        "success", false,
                        "error", e.getMessage(),
                        "namespace", "default"
                ));
                log.error("❌ Failed to list pods in default: {}", e.getMessage());
            }

            result.put("resourceTests", resourceTests);
            result.put("success", true);
            result.put("kubernetesVersion", version);
            result.put("message", "Successfully connected to Kubernetes cluster using kubeconfig");
            result.put("authMethod", cluster.getClientToken() != null && !cluster.getClientToken().trim().isEmpty() ? "token" : "certificate");

            // 클라이언트 닫기
            client.close();

        } catch (Exception e) {
            log.error("Failed to connect to Kubernetes cluster: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("errorType", e.getClass().getSimpleName());

            // 원인 에러 추가
            Throwable cause = e.getCause();
            if (cause != null) {
                result.put("causeError", cause.getMessage());
                result.put("causeErrorType", cause.getClass().getSimpleName());

                // DER 관련 에러인지 확인
                if (cause.getMessage() != null && cause.getMessage().contains("DER")) {
                    result.put("isDerError", true);
                    result.put("derErrorDetails", cause.getMessage());
                }
            }

            // 스택 트레이스의 첫 번째 몇 줄 추가
            if (e.getStackTrace().length > 0) {
                StringBuilder stackTrace = new StringBuilder();
                for (int i = 0; i < Math.min(10, e.getStackTrace().length); i++) {
                    stackTrace.append(e.getStackTrace()[i].toString()).append("\n");
                }
                result.put("stackTrace", stackTrace.toString());
            }
        }

        return new ResponseEntity<>(result, new HttpHeaders(), HttpStatus.OK);
    }

    @GetMapping("/kubeconfig/{clusterId}")
    public ResponseEntity<String> generateKubeconfig(@PathVariable String clusterId) {
        try {
            ClusterEntity cluster = clusterRepository.findById(clusterId)
                    .orElseThrow(() -> new RuntimeException("Cluster not found: " + clusterId));

            // kubeconfig 생성
            String kubeconfigContent = KubernetesClientConfig.createKubeconfigContent(cluster);

            return ResponseEntity.ok()
                    .header("Content-Type", "application/x-yaml")
                    .header("Content-Disposition", "attachment; filename=" + clusterId + "-kubeconfig.yaml")
                    .body(kubeconfigContent);

        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body("Error generating kubeconfig: " + e.getMessage());
        }
    }
}