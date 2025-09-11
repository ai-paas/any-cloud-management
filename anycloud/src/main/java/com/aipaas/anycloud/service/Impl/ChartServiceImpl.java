package com.aipaas.anycloud.service.Impl;

import com.aipaas.anycloud.configuration.bean.KubernetesClientConfig;
import com.aipaas.anycloud.error.exception.HelmChartNotFoundException;
import com.aipaas.anycloud.error.exception.HelmDeploymentException;
import com.aipaas.anycloud.error.exception.HelmRepositoryNotFoundException;
import com.aipaas.anycloud.model.dto.request.ChartDeployDto;
import com.aipaas.anycloud.model.dto.response.*;
import com.aipaas.anycloud.model.entity.ClusterEntity;
import com.aipaas.anycloud.model.entity.HelmRepoEntity;
import com.aipaas.anycloud.service.ChartService;
import com.aipaas.anycloud.service.ClusterService;
import com.aipaas.anycloud.service.HelmRepoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

/**
 * <pre>
 * ClassName : ChartServiceImpl
 * Type : class
 * Description : Helm 차트 관련 기능을 구현한 서비스 클래스입니다.
 * Related : ChartService, ChartController
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChartServiceImpl implements ChartService {

    private final HelmRepoService helmRepoService;
    private final ClusterService clusterService;
    private final RestTemplate restTemplate;

    @Override
    public ChartListDto getChartList(String repositoryName) {
        log.info("Getting chart list for repository: {}", repositoryName);
    
        HelmRepoEntity repository = getRepository(repositoryName);
    
        try {
            // Helm repository의 index.yaml을 다운로드하여 파싱
            String indexUrl = repository.getUrl().endsWith("/") ? 
                repository.getUrl() + "index.yaml" : 
                repository.getUrl() + "/index.yaml";
    
            log.debug("Fetching index.yaml from URL: {}", indexUrl);
    
        
            HttpHeaders headers = createAuthHeaders(repository);
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            ResponseEntity<String> response = restTemplate.exchange(
                indexUrl, 
                HttpMethod.GET, 
                entity, 
                String.class
            );
        
            log.debug("Response status: {}, Content length: {}", 
                response.getStatusCode(), 
                response.getBody() != null ? response.getBody().length() : 0);
    
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseIndexYaml(repositoryName, response.getBody());
            } else {
                throw new HelmChartNotFoundException(
                    "Unable to fetch index.yaml from repository: " + repositoryName + 
                    " (HTTP " + response.getStatusCode() + ")"
                );
            }
    
        } catch (HelmChartNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get chart list for repository: {} from URL: {}", repositoryName, repository.getUrl(), e);
            throw new HelmChartNotFoundException(
                "Failed to fetch charts from repository: " + repositoryName + 
                " - " + e.getMessage()
            );
        }
    }

    @Override
    public ChartDetailDto getChartDetail(String repositoryName, String chartName) {
    log.info("Getting chart detail for repository: {}, chart: {}", repositoryName, chartName);

    HelmRepoEntity repository = getRepository(repositoryName);

    try {
        // Helm repository의 index.yaml을 다운로드하여 파싱
        String indexUrl = repository.getUrl().endsWith("/") ? 
            repository.getUrl() + "index.yaml" : 
            repository.getUrl() + "/index.yaml";

        log.debug("Fetching index.yaml from URL: {}", indexUrl);

        HttpHeaders headers = createAuthHeaders(repository);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
            indexUrl,
            HttpMethod.GET,
            entity,
            String.class
        );

        log.debug("Response status: {}, Content length: {}", 
            response.getStatusCode(), 
            response.getBody() != null ? response.getBody().length() : 0);

        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            return parseChartDetail(repositoryName, response.getBody(), chartName);
        } else {
            throw new HelmChartNotFoundException(
                "Unable to fetch index.yaml from repository: " + repositoryName + 
                " (HTTP " + response.getStatusCode() + ")"
            );
        }

    } catch (HelmChartNotFoundException e) {
        throw e;
    } catch (Exception e) {
        log.error("Failed to get chart detail for repository: {}, chart: {} from URL: {}", repositoryName, chartName, repository.getUrl(), e);
        throw new HelmChartNotFoundException(
            "Failed to fetch chart detail from repository: " + repositoryName + 
            " - " + e.getMessage()
        );
    }
}

    @Override
    public ChartValuesDto getChartValues(String repositoryName, String chartName, String version) {
        log.info("Getting values for chart: {}/{}, version: {}", repositoryName, chartName, version);

        HelmRepoEntity repository = getRepository(repositoryName);

        try {
            // Helm CLI를 사용하여 values.yaml 조회
            String command = buildHelmShowCommand("values", repository, chartName, version);
            String valuesContent = executeHelmCommandWithoutKubeconfig(command);

            return ChartValuesDto.builder()
                .repositoryName(repositoryName)
                .chartName(chartName)
                .version(version)
                .valuesContent(valuesContent)
                .build();

        } catch (Exception e) {
            log.error("Failed to get values for chart: {}/{}", repositoryName, chartName, e);
            throw new HelmChartNotFoundException(repositoryName, chartName);
        }
    }

    @Override
    public ChartReadmeDto getChartReadme(String repositoryName, String chartName, String version) {
        log.info("Getting README for chart: {}/{}, version: {}", repositoryName, chartName, version);

        HelmRepoEntity repository = getRepository(repositoryName);

        try {
            // Helm CLI를 사용하여 README.md 조회
            String command = buildHelmShowCommand("readme", repository, chartName, version);
            String readmeContent = executeHelmCommandWithoutKubeconfig(command);

            return ChartReadmeDto.builder()
                .repositoryName(repositoryName)
                .chartName(chartName)
                .version(version)
                .readmeContent(readmeContent)
                .build();

        } catch (Exception e) {
            log.error("Failed to get README for chart: {}/{}", repositoryName, chartName, e);
            throw new HelmChartNotFoundException(repositoryName, chartName);
        }
    }

    @Override
    public ChartDeployResponseDto deployChart(String repositoryName, String chartName, ChartDeployDto deployDto) {
        log.info("Deploying chart: {}/{} as release: {} to cluster: {}", 
                repositoryName, chartName, deployDto.getReleaseName(), deployDto.getClusterId());

        HelmRepoEntity repository = getRepository(repositoryName);
        ClusterEntity cluster = getCluster(deployDto.getClusterId());

        // 클러스터 연결 테스트
        if (!testClusterConnection(cluster)) {
            throw new HelmDeploymentException("Cannot connect to cluster: " + deployDto.getClusterId() + ". Please check cluster configuration.");
        }

        try {
            // kubeconfig 파일 생성
            String kubeconfigPath = createKubeconfigFile(cluster);
            
            try {
                // Helm CLI를 사용하여 차트 배포 (kubeconfig 사용)
                String command = buildHelmInstallCommand(repository, chartName, deployDto, kubeconfigPath);
                String output = executeHelmCommand(command, kubeconfigPath);

                return ChartDeployResponseDto.builder()
                    .success(true)
                    .releaseName(deployDto.getReleaseName())
                    .namespace(deployDto.getNamespace() != null ? deployDto.getNamespace() : "default")
                    .chartVersion(deployDto.getVersion())
                    .clusterId(deployDto.getClusterId())
                    .status("deployed")
                    .message("Release " + deployDto.getReleaseName() + " has been deployed successfully to cluster " + deployDto.getClusterId())
                    .output(output)
                    .build();
            } finally {
                // 임시 kubeconfig 파일 삭제
                deleteKubeconfigFile(kubeconfigPath);
            }

        } catch (Exception e) {
            log.error("Failed to deploy chart: {}/{} to cluster: {}", repositoryName, chartName, deployDto.getClusterId(), e);
            throw new HelmDeploymentException("Failed to deploy chart: " + repositoryName + "/" + chartName + " to cluster " + deployDto.getClusterId() + ". " + e.getMessage());
        }
    }

    @Override
    public ChartDeployResponseDto getChartStatus(String releaseName, String clusterId, String namespace) {
        log.info("Getting chart status for release: {} in cluster: {}", releaseName, clusterId);

        ClusterEntity cluster = getCluster(clusterId);
        String targetNamespace = namespace != null ? namespace : "default";

        try {
            // kubeconfig 파일 생성
            String kubeconfigPath = createKubeconfigFile(cluster);
            
            try {
                // Helm CLI를 사용하여 릴리즈 상태 조회
                String command = buildHelmStatusCommand(releaseName, targetNamespace, kubeconfigPath);
                String output = executeHelmCommand(command, kubeconfigPath);

                return ChartDeployResponseDto.builder()
                    .success(true)
                    .releaseName(releaseName)
                    .namespace(targetNamespace)
                    .clusterId(clusterId)
                    .status("checked")
                    .message("Release " + releaseName + " status retrieved successfully")
                    .output(output)
                    .build();

            } finally {
                // 임시 kubeconfig 파일 삭제
                deleteKubeconfigFile(kubeconfigPath);
            }

        } catch (Exception e) {
            log.error("Failed to get chart status for release: {} in cluster: {}", releaseName, clusterId, e);
            throw new HelmDeploymentException("Failed to get chart status for release: " + releaseName + " in cluster " + clusterId + ". " + e.getMessage());
        }
    }

    private HelmRepoEntity getRepository(String repositoryName) {
        if (!helmRepoService.isHelmExist(repositoryName)) {
            throw new HelmRepositoryNotFoundException(repositoryName);
        }
        return helmRepoService.getHelmRepo(repositoryName);
    }

    private ClusterEntity getCluster(String clusterId) {
        if (clusterId == null || clusterId.trim().isEmpty()) {
            throw new IllegalArgumentException("Cluster ID is required for chart deployment");
        }
        return clusterService.getCluster(clusterId);
    }

    /**
     * Fabric8 Kubernetes Client를 사용하여 클러스터 정보를 기반으로 임시 kubeconfig 파일을 생성합니다.
     */
    private String createKubeconfigFile(ClusterEntity cluster) throws IOException {
        try {
            // Fabric8 Kubernetes Client를 사용하여 클러스터 정보 조회
            KubernetesClientConfig k8sConfig = new KubernetesClientConfig(cluster);
            KubernetesClient client = k8sConfig.getClient();
            
            try {
                // 클러스터 연결 테스트
                client.getApiVersion();
                
                // Fabric8의 Config 객체를 사용하여 kubeconfig 생성
                Config fabric8Config = client.getConfiguration();
                
                // kubeconfig YAML 생성
                StringBuilder kubeconfig = new StringBuilder();
                kubeconfig.append("apiVersion: v1\n");
                kubeconfig.append("kind: Config\n");
                kubeconfig.append("clusters:\n");
                kubeconfig.append("- cluster:\n");
                kubeconfig.append("    server: ").append(fabric8Config.getMasterUrl()).append("\n");
                
                // CA 인증서 처리
                if (fabric8Config.getCaCertData() != null && !fabric8Config.getCaCertData().isEmpty()) {
                    kubeconfig.append("    certificate-authority-data: ").append(fabric8Config.getCaCertData()).append("\n");
                } else if (fabric8Config.getCaCertFile() != null) {
                    kubeconfig.append("    certificate-authority: ").append(fabric8Config.getCaCertFile()).append("\n");
                }
                
                // 클러스터 이름은 DB의 ID 사용
                String clusterName = cluster.getId();
                kubeconfig.append("  name: ").append(clusterName).append("\n");
                
                kubeconfig.append("contexts:\n");
                kubeconfig.append("- context:\n");
                kubeconfig.append("    cluster: ").append(clusterName).append("\n");
                kubeconfig.append("    user: ").append(clusterName).append("-user\n");
                kubeconfig.append("  name: ").append(clusterName).append("\n");
                kubeconfig.append("current-context: ").append(clusterName).append("\n");
                
                kubeconfig.append("users:\n");
                kubeconfig.append("- name: ").append(clusterName).append("-user\n");
                kubeconfig.append("  user:\n");
                
                // 인증 정보 처리
                if (fabric8Config.getOauthToken() != null && !fabric8Config.getOauthToken().isEmpty()) {
                    kubeconfig.append("    token: ").append(fabric8Config.getOauthToken()).append("\n");
                } else {
                    // 클라이언트 인증서 사용
                    if (fabric8Config.getClientCertData() != null && !fabric8Config.getClientCertData().isEmpty()) {
                        kubeconfig.append("    client-certificate-data: ").append(fabric8Config.getClientCertData()).append("\n");
                    } else if (fabric8Config.getClientCertFile() != null) {
                        kubeconfig.append("    client-certificate: ").append(fabric8Config.getClientCertFile()).append("\n");
                    }
                    
                    if (fabric8Config.getClientKeyData() != null && !fabric8Config.getClientKeyData().isEmpty()) {
                        kubeconfig.append("    client-key-data: ").append(fabric8Config.getClientKeyData()).append("\n");
                    } else if (fabric8Config.getClientKeyFile() != null) {
                        kubeconfig.append("    client-key: ").append(fabric8Config.getClientKeyFile()).append("\n");
                    }
                }

                // 임시 파일 생성
                String tempDir = System.getProperty("java.io.tmpdir");
                String fileName = "kubeconfig_" + cluster.getId() + "_" + System.currentTimeMillis() + ".yaml";
                Path kubeconfigPath = Paths.get(tempDir, fileName);
                
                Files.write(kubeconfigPath, kubeconfig.toString().getBytes(StandardCharsets.UTF_8));
                
                log.debug("Created temporary kubeconfig file using Fabric8: {}", kubeconfigPath.toString());
                return kubeconfigPath.toString();
                
            } finally {
                // 클라이언트 정리
                k8sConfig.closeClient();
            }
            
        } catch (Exception e) {
            log.error("Failed to create kubeconfig using Fabric8 for cluster: {}", cluster.getId(), e);
            throw new IOException("Failed to create kubeconfig for cluster " + cluster.getId() + ": " + e.getMessage(), e);
        }
    }

    /**
     * 임시 kubeconfig 파일을 삭제합니다.
     */
    private void deleteKubeconfigFile(String kubeconfigPath) {
        try {
            Path path = Paths.get(kubeconfigPath);
            if (Files.exists(path)) {
                Files.delete(path);
                log.debug("Deleted temporary kubeconfig file: {}", kubeconfigPath);
            }
        } catch (IOException e) {
            log.warn("Failed to delete temporary kubeconfig file: {}", kubeconfigPath, e);
        }
    }

    /**
     * Fabric8 Kubernetes Client를 사용하여 클러스터 연결을 테스트합니다.
     */
    private boolean testClusterConnection(ClusterEntity cluster) {
        try {
            KubernetesClientConfig k8sConfig = new KubernetesClientConfig(cluster);
            KubernetesClient client = k8sConfig.getClient();
            
            try {
                // 간단한 API 호출로 연결 테스트
                client.getApiVersion();
                log.info("Successfully connected to cluster: {}", cluster.getId());
                return true;
            } catch (Exception e) {
                log.error("Failed to connect to cluster: {}", cluster.getId(), e);
                return false;
            } finally {
                k8sConfig.closeClient();
            }
        } catch (Exception e) {
            log.error("Error testing cluster connection: {}", cluster.getId(), e);
            return false;
        }
    }

    /**
     * index.yaml 내용을 파싱하여 차트 목록을 생성합니다.
     */
    private ChartListDto parseIndexYaml(String repositoryName, String indexContent) {
        try {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            JsonNode rootNode = yamlMapper.readTree(indexContent);
            JsonNode entriesNode = rootNode.get("entries");

            List<ChartListDto.ChartInfo> charts = new ArrayList<>();

            if (entriesNode != null && entriesNode.isObject()) {
                entriesNode.fieldNames().forEachRemaining(chartName -> {
                    JsonNode chartVersions = entriesNode.get(chartName);
                    if (chartVersions.isArray() && chartVersions.size() > 0) {
                        // 최신 버전만 사용 (첫 번째 요소)
                        JsonNode latestVersion = chartVersions.get(0);
                        JsonNode keywordsNode = latestVersion.path("keywords");
                        String[] keywords = null;
                        if (keywordsNode.isArray()) {
                            keywords = StreamSupport.stream(keywordsNode.spliterator(), false)
                                    .map(JsonNode::asText)
                                    .toArray(String[]::new);
                        }
                        

                        ChartListDto.ChartInfo chartInfo = ChartListDto.ChartInfo.builder()
                            .name(chartName)
                            .version(latestVersion.path("version").asText())
                            .description(latestVersion.path("description").asText(null))
                            .appVersion(latestVersion.path("appVersion").asText(null))
                            .keywords(keywords)
                            .created(latestVersion.path("created").asText(null))
                            .build();

                        charts.add(chartInfo);
                    }
                });
            }

            return ChartListDto.builder()
                .repositoryName(repositoryName)
                .charts(charts)
                .build();

        } catch (Exception e) {
            log.error("Failed to parse index.yaml", e);
            throw new HelmChartNotFoundException("Failed to parse repository index: " + repositoryName);
        }
    }


    /**
     * index.yaml 내용을 파싱하여 특정 차트의 상세 정보를 반환합니다.
     */
    private ChartDetailDto parseChartDetail(String repositoryName, String indexContent, String targetChartName) {
        try {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            JsonNode rootNode = yamlMapper.readTree(indexContent);
            JsonNode entriesNode = rootNode.get("entries");
    
            if (entriesNode != null && entriesNode.isObject() && entriesNode.has(targetChartName)) {
                JsonNode chartVersions = entriesNode.get(targetChartName);
                if (chartVersions.isArray() && chartVersions.size() > 0) {
    
                    // 버전 히스토리 생성
                    List<ChartDetailDto.VersionHistory> versionHistory = StreamSupport.stream(chartVersions.spliterator(), false)
                            .map(v -> ChartDetailDto.VersionHistory.builder()
                                    .version(v.path("version").asText())
                                    .appVersion(v.path("appVersion").asText())
                                    .created(v.path("created").asText(null))
                                    .build())
                            .toList();
    
                    // 최신 버전 정보 (첫 번째 요소)
                    JsonNode latestVersion = chartVersions.get(0);
    
                    // keywords 처리
                    JsonNode keywordsNode = latestVersion.path("keywords");
                    String[] keywords = null;
                    if (keywordsNode.isArray()) {
                        keywords = StreamSupport.stream(keywordsNode.spliterator(), false)
                                .map(JsonNode::asText)
                                .toArray(String[]::new);
                    }
    
                    // maintainers 처리
                    JsonNode maintainersNode = latestVersion.path("maintainers");
                    List<Map<String, Object>> maintainers = null;
                    if (maintainersNode.isArray()) {
                        maintainers = StreamSupport.stream(maintainersNode.spliterator(), false)
                                .map(node -> {
                                    Map<String, Object> map = new HashMap<>();
                                    node.fieldNames().forEachRemaining(field -> {
                                        map.put(field, node.path(field).asText(null));
                                    });
                                    return map;
                                })
                                .toList();
                    }
    
                    // dependencies 처리
                    JsonNode dependenciesNode = latestVersion.path("dependencies");
                    List<ChartDetailDto.Dependency> dependencies = null;
                    if (dependenciesNode.isArray()) {
                        dependencies = StreamSupport.stream(dependenciesNode.spliterator(), false)
                                .map(dep -> ChartDetailDto.Dependency.builder()
                                        .name(dep.path("name").asText(null))
                                        .version(dep.path("version").asText(null))
                                        .repository(dep.path("repository").asText(null))
                                        .build())
                                .toList();
                    }
    
                    return ChartDetailDto.builder()
                            .repositoryName(repositoryName)
                            .name(targetChartName)
                            .version(latestVersion.path("version").asText())
                            .description(latestVersion.path("description").asText(null))
                            .appVersion(latestVersion.path("appVersion").asText(null))
                            .keywords(keywords)
                            .created(latestVersion.path("created").asText(null))
                            .maintainers(maintainers)
                            .source(latestVersion.path("sources").isArray() && latestVersion.path("sources").size() > 0
                                    ? latestVersion.path("sources").get(0).asText(null)
                                    : null)
                            .home(latestVersion.path("home").asText(null))
                            .icon(latestVersion.path("icon").asText(null))
                            .dependencies(dependencies)
                            .versionHistory(versionHistory)
                            .build();
                }
            }
    
            throw new HelmChartNotFoundException("Chart not found: " + targetChartName + " in repository " + repositoryName);
    
        } catch (Exception e) {
            log.error("Failed to parse index.yaml", e);
            throw new HelmChartNotFoundException("Failed to parse repository index: " + repositoryName);
        }
    }
    
    /**
     * Helm show 명령어를 빌드합니다.
     */
    private String buildHelmShowCommand(String showType, HelmRepoEntity repository, String chartName, String version) {
        // 먼저 repository를 추가
        String repoAddCommand = buildHelmRepoAddCommand(repository);
        
        StringBuilder command = new StringBuilder();
        command.append(repoAddCommand).append(" && ");
        command.append("helm show ").append(showType).append(" ");
        command.append(repository.getName()).append("/").append(chartName);

        if (version != null && !version.trim().isEmpty()) {
            command.append(" --version ").append(version);
        }

        return command.toString();
    }

    /**
     * Helm repo add 명령어를 빌드합니다.
     */
    private String buildHelmRepoAddCommand(HelmRepoEntity repository) {
        StringBuilder command = new StringBuilder("helm repo add ");
        command.append(repository.getName()).append(" ").append(repository.getUrl());

        // Basic Authentication 처리
        if (repository.getUsername() != null && repository.getPassword() != null && 
            !repository.getUsername().trim().isEmpty() && !repository.getPassword().trim().isEmpty()) {
            command.append(" --username ").append(repository.getUsername());
            command.append(" --password ").append(repository.getPassword());
            log.debug("Added authentication for repository: {}", repository.getName());
        }

        // CA File 처리
        if (repository.getCaFile() != null && !repository.getCaFile().trim().isEmpty()) {
            // CA 파일을 임시로 저장하고 경로 지정
            command.append(" --ca-file /tmp/ca.crt");
            log.debug("Added CA file for repository: {}", repository.getName());
        }

        // TLS 검증 생략 처리
        if (repository.getInsecureSkipTlsVerify() != null && repository.getInsecureSkipTlsVerify()) {
            command.append(" --insecure-skip-tls-verify");
            log.debug("Added insecure-skip-tls-verify for repository: {}", repository.getName());
        }

        return command.toString();
    }

    /**
     * Helm install 명령어를 빌드합니다.
     */
    private String buildHelmInstallCommand(HelmRepoEntity repository, String chartName, ChartDeployDto deployDto, String kubeconfigPath) {
        // 먼저 repository를 추가
        String repoAddCommand = buildHelmRepoAddCommand(repository);
        
        StringBuilder command = new StringBuilder();
        command.append(repoAddCommand).append(" && ");
        command.append("helm install ")
            .append(deployDto.getReleaseName())
            .append(" ")
            .append(repository.getName())
            .append("/")
            .append(chartName);

        // kubeconfig 파일 지정
        command.append(" --kubeconfig ").append(kubeconfigPath);

        if (deployDto.getNamespace() != null && !deployDto.getNamespace().trim().isEmpty()) {
            command.append(" --namespace ").append(deployDto.getNamespace())
                   .append(" --create-namespace");
        }

        if (deployDto.getVersion() != null && !deployDto.getVersion().trim().isEmpty()) {
            command.append(" --version ").append(deployDto.getVersion());
        }

        if (deployDto.getWait() != null && deployDto.getWait()) {
            command.append(" --wait");
        }

        if (deployDto.getTimeout() != null && deployDto.getTimeout() > 0) {
            command.append(" --timeout ").append(deployDto.getTimeout()).append("s");
        }

        // values 오버라이드 처리
        if (deployDto.getValues() != null && !deployDto.getValues().isEmpty()) {
            addNestedValuesToCommand(command, deployDto.getValues(), "");
        }

        return command.toString();
    }

    /**
     * Helm 명령어에서 사용할 값 포맷팅
     */
    private String formatValueForHelm(Object value) {
        if (value == null) {
            return "";
        }
        
        if (value instanceof String) {
            return (String) value;
        }
        
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        
        if (value instanceof Map) {
            // 중첩된 객체는 JSON 문자열로 변환
            try {
                ObjectMapper mapper = new ObjectMapper();
                return mapper.writeValueAsString(value);
            } catch (Exception e) {
                log.warn("Failed to convert value to JSON: {}", value, e);
                return value.toString();
            }
        }
        
        return value.toString();
    }

    /**
     * 중첩된 Map을 Helm의 점 표기법으로 변환
     */
    private void addNestedValuesToCommand(StringBuilder command, Map<String, Object> values, String prefix) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) value;
                addNestedValuesToCommand(command, nestedMap, key);
            } else {
                command.append(" --set ")
                       .append(key)
                       .append("=")
                       .append(formatValueForHelm(value));
            }
        }
    }

    /**
     * Helm status 명령어를 빌드합니다.
     */
    private String buildHelmStatusCommand(String releaseName, String namespace, String kubeconfigPath) {
        StringBuilder command = new StringBuilder();
        command.append("helm status ")
            .append(releaseName);

        // kubeconfig 파일 지정
        command.append(" --kubeconfig ").append(kubeconfigPath);

        if (namespace != null && !namespace.trim().isEmpty()) {
            command.append(" --namespace ").append(namespace);
        }

        return command.toString();
    }

    private String executeHelmCommand(String command, String kubeconfigPath) throws IOException, InterruptedException {
        log.debug("Executing helm command: {}", command);

        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("sh", "-c", command);
        processBuilder.redirectErrorStream(true);
        
        // kubeconfig 환경변수 설정
        Map<String, String> environment = processBuilder.environment();
        environment.put("KUBECONFIG", kubeconfigPath);

        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new HelmDeploymentException("Helm command timed out: " + command);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new HelmDeploymentException("Helm command failed with exit code " + exitCode + ": " + output.toString());
        }

        return output.toString();
    }

    private String executeHelmCommandWithoutKubeconfig(String command) throws IOException, InterruptedException {
        log.debug("Executing helm command (without kubeconfig): {}", command);

        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("sh", "-c", command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new HelmDeploymentException("Helm command timed out: " + command);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new HelmDeploymentException("Helm command failed with exit code " + exitCode + ": " + output.toString());
        }

        return output.toString();
    }

    private HttpHeaders createAuthHeaders(HelmRepoEntity repository) {
        HttpHeaders headers = new HttpHeaders();
        
        if (repository.getUsername() != null && repository.getPassword() != null) {
            String auth = repository.getUsername() + ":" + repository.getPassword();
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            headers.set("Authorization", "Basic " + encodedAuth);
        }
        
        return headers;
    }
}
