package com.aipaas.anycloud.service.Impl;

import com.aipaas.anycloud.configuration.bean.KubernetesClientConfig;
import com.aipaas.anycloud.error.exception.HelmChartNotFoundException;
import com.aipaas.anycloud.error.exception.HelmDeploymentException;
import com.aipaas.anycloud.error.exception.HelmRepositoryNotFoundException;
import com.aipaas.anycloud.model.dto.response.*;
import com.aipaas.anycloud.model.entity.ClusterEntity;
import com.aipaas.anycloud.model.entity.HelmRepoEntity;
import com.aipaas.anycloud.service.ChartService;
import com.aipaas.anycloud.service.ClusterService;
import com.aipaas.anycloud.service.HelmRepoService;
import com.aipaas.anycloud.service.util.HelmCommandExecutor;
import com.aipaas.anycloud.service.util.ChartValidator;
import com.aipaas.anycloud.service.util.ChartParser;
import com.aipaas.anycloud.service.util.DeploymentOrchestrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
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
    private final HelmCommandExecutor helmCommandExecutor;
    private final ChartValidator chartValidator;
    private final ChartParser chartParser;
    private final DeploymentOrchestrator deploymentOrchestrator;

    @Override
    public ChartListDto getChartList(String repositoryName) {
        log.info("Getting chart list for repository: {}", repositoryName);

        HelmRepoEntity repository = getRepository(repositoryName);

        try {
            // Helm repository의 index.yaml을 다운로드하여 파싱
            String indexUrl = repository.getUrl().endsWith("/") ? repository.getUrl() + "index.yaml"
                    : repository.getUrl() + "/index.yaml";

            log.debug("Fetching index.yaml from URL: {}", indexUrl);

            HttpHeaders headers = createAuthHeaders(repository);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    indexUrl,
                    HttpMethod.GET,
                    entity,
                    String.class);

            log.debug("Response status: {}, Content length: {}",
                    response.getStatusCode(),
                    response.getBody() != null ? response.getBody().length() : 0);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return chartParser.parseIndexYaml(repositoryName, response.getBody());
            } else {
                throw new HelmChartNotFoundException(
                        "Unable to fetch index.yaml from repository: " + repositoryName +
                                " (HTTP " + response.getStatusCode() + ")");
            }

        } catch (HelmChartNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get chart list for repository: {} from URL: {}", repositoryName, repository.getUrl(),
                    e);
            throw new HelmChartNotFoundException(
                    "Failed to fetch charts from repository: " + repositoryName +
                            " - " + e.getMessage());
        }
    }

    @Override
    public ChartDetailDto getChartDetail(String repositoryName, String chartName) {
        log.info("Getting chart detail for repository: {}, chart: {}", repositoryName, chartName);

        HelmRepoEntity repository = getRepository(repositoryName);

        try {
            // Helm repository의 index.yaml을 다운로드하여 파싱
            String indexUrl = repository.getUrl().endsWith("/") ? repository.getUrl() + "index.yaml"
                    : repository.getUrl() + "/index.yaml";

            log.debug("Fetching index.yaml from URL: {}", indexUrl);

            HttpHeaders headers = createAuthHeaders(repository);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    indexUrl,
                    HttpMethod.GET,
                    entity,
                    String.class);

            log.debug("Response status: {}, Content length: {}",
                    response.getStatusCode(),
                    response.getBody() != null ? response.getBody().length() : 0);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return chartParser.parseChartDetail(repositoryName, chartName, response.getBody());
            } else {
                throw new HelmChartNotFoundException(
                        "Unable to fetch index.yaml from repository: " + repositoryName +
                                " (HTTP " + response.getStatusCode() + ")");
            }

        } catch (HelmChartNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get chart detail for repository: {}, chart: {} from URL: {}", repositoryName,
                    chartName, repository.getUrl(), e);
            throw new HelmChartNotFoundException(
                    "Failed to fetch chart detail from repository: " + repositoryName +
                            " - " + e.getMessage());
        }
    }

    @Override
    public ChartValuesDto getChartValues(String repositoryName, String chartName, String version) {
        log.info("Getting values for chart: {}/{}, version: {}", repositoryName, chartName, version);

        HelmRepoEntity repository = getRepository(repositoryName);

        try {
            // Helm CLI를 사용하여 values.yaml 조회
            String command = helmCommandExecutor.buildHelmShowCommand("values", repository, chartName, version);
            String valuesContent = helmCommandExecutor.executeHelmCommandWithoutKubeconfig(command);

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
            String command = helmCommandExecutor.buildHelmShowCommand("readme", repository, chartName, version);
            String readmeContent = helmCommandExecutor.executeHelmCommandWithoutKubeconfig(command);

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
    public ChartDeployResponseDto deployChart(String repositoryName, String chartName, String releaseName,
            String clusterId, String namespace, String version, MultipartFile valuesFile) {
        log.info("Starting deployment request for chart: {}/{} as release: {} to cluster: {}",
                repositoryName, chartName, releaseName, clusterId);

        HelmRepoEntity repository = getRepository(repositoryName);
        ClusterEntity cluster = getCluster(clusterId);

        // kubeconfig 파일 생성 및 Kubernetes 클러스터 응답 테스트
        try {
            String testKubeconfigPath = createKubeconfigFile(cluster);
            
            try {
                KubernetesClientConfig manager = new KubernetesClientConfig(cluster);
                KubernetesClient client = manager.getClient();
                client.getApiVersion();
                
                // 전체 배포 사전 검증 수행
                chartValidator.validateBeforeDeployment(repositoryName, chartName, releaseName, 
                    clusterId, namespace, cluster.getVersion(), testKubeconfigPath, repository);
              
                
            } finally {
                deleteKubeconfigFile(testKubeconfigPath); // 테스트 후 즉시 삭제
            }
            
        } catch (Exception e) {
            log.error("Failed kubeconfig or connectivity test for cluster: {}", clusterId, e);
            throw new HelmDeploymentException(
                    "Cannot connect to cluster: " + clusterId + ". Error: " + e.getMessage());
        }

        // 비동기로 배포 실행 (DeploymentOrchestrator 사용)
        deploymentOrchestrator.executeDeploymentAsync(repository, chartName, releaseName, clusterId, namespace, 
                version, valuesFile, cluster, this::createKubeconfigFile, this::deleteKubeconfigFile);

        log.info("Deployment request submitted for release: {} to cluster: {}", releaseName, clusterId);

        return ChartDeployResponseDto.builder()
                .success(true)
                .message("Deployment request submitted for release " + releaseName + " to cluster " + clusterId
                        + ". Check status later.")
                .build();
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
                String command = helmCommandExecutor.buildHelmStatusCommand(releaseName, targetNamespace, kubeconfigPath);
                String output = helmCommandExecutor.executeHelmCommand(command, kubeconfigPath);

                // Helm 상태 출력 파싱
                String status = chartParser.parseHelmStatusOutput(output);

                return ChartDeployResponseDto.builder()
                        .success(true)
                        .message("Release " + releaseName + " status: " + status)
                        .build();

            } finally {
                // 임시 kubeconfig 파일 삭제
                deleteKubeconfigFile(kubeconfigPath);
            }

        } catch (Exception e) {
            log.error("Failed to get chart status for release: {} in cluster: {}", releaseName, clusterId, e);
            return ChartDeployResponseDto.builder()
                    .success(false)
                    .message("Failed to get chart status for release: " + releaseName + " in cluster " + clusterId
                            + ". " + e.getMessage())
                    .build();
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
     * 클러스터 정보를 기반으로 임시 kubeconfig 파일을 생성합니다.
     */
    private String createKubeconfigFile(ClusterEntity cluster) throws IOException {
        try {
            // KubernetesClientConfig의 createKubeconfigContent 메서드 사용 (중복 제거)
            String kubeconfigContent = KubernetesClientConfig.createKubeconfigContent(cluster);

            // 임시 파일 생성
            String tempDir = System.getProperty("java.io.tmpdir");
            String fileName = "kubeconfig_" + cluster.getId() + "_" + System.currentTimeMillis() + ".yaml";
            Path kubeconfigPath = Paths.get(tempDir, fileName);

            Files.write(kubeconfigPath, kubeconfigContent.getBytes(StandardCharsets.UTF_8));

            log.debug("Created temporary kubeconfig file: {}", kubeconfigPath.toString());
            return kubeconfigPath.toString();

        } catch (Exception e) {
            log.error("Failed to create kubeconfig file for cluster: {}", cluster.getId(), e);
            throw new IOException("Failed to create kubeconfig for cluster " + cluster.getId() + ": " + e.getMessage(),
                    e);
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




    // /**
    //  * index.yaml 내용을 파싱하여 특정 차트의 상세 정보를 반환합니다.
    //  */
    // private ChartDetailDto parseChartDetail(String repositoryName, String indexContent, String targetChartName) {
    //     try {
    //         ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    //         JsonNode rootNode = yamlMapper.readTree(indexContent);
    //         JsonNode entriesNode = rootNode.get("entries");

    //         if (entriesNode != null && entriesNode.isObject() && entriesNode.has(targetChartName)) {
    //             JsonNode chartVersions = entriesNode.get(targetChartName);
    //             if (chartVersions.isArray() && chartVersions.size() > 0) {

    //                 // 버전 히스토리 생성
    //                 List<ChartDetailDto.VersionHistory> versionHistory = StreamSupport
    //                         .stream(chartVersions.spliterator(), false)
    //                         .map(v -> ChartDetailDto.VersionHistory.builder()
    //                                 .version(v.path("version").asText())
    //                                 .appVersion(v.path("appVersion").asText())
    //                                 .created(v.path("created").asText(null))
    //                                 .build())
    //                         .toList();

    //                 // 최신 버전 정보 (첫 번째 요소)
    //                 JsonNode latestVersion = chartVersions.get(0);

    //                 // keywords 처리
    //                 JsonNode keywordsNode = latestVersion.path("keywords");
    //                 String[] keywords = null;
    //                 if (keywordsNode.isArray()) {
    //                     keywords = StreamSupport.stream(keywordsNode.spliterator(), false)
    //                             .map(JsonNode::asText)
    //                             .toArray(String[]::new);
    //                 }

    //                 // maintainers 처리
    //                 JsonNode maintainersNode = latestVersion.path("maintainers");
    //                 List<Map<String, Object>> maintainers = null;
    //                 if (maintainersNode.isArray()) {
    //                     maintainers = StreamSupport.stream(maintainersNode.spliterator(), false)
    //                             .map(node -> {
    //                                 Map<String, Object> map = new HashMap<>();
    //                                 node.fieldNames().forEachRemaining(field -> {
    //                                     map.put(field, node.path(field).asText(null));
    //                                 });
    //                                 return map;
    //                             })
    //                             .toList();
    //                 }

    //                 // dependencies 처리
    //                 JsonNode dependenciesNode = latestVersion.path("dependencies");
    //                 List<ChartDetailDto.Dependency> dependencies = null;
    //                 if (dependenciesNode.isArray()) {
    //                     dependencies = StreamSupport.stream(dependenciesNode.spliterator(), false)
    //                             .map(dep -> ChartDetailDto.Dependency.builder()
    //                                     .name(dep.path("name").asText(null))
    //                                     .version(dep.path("version").asText(null))
    //                                     .repository(dep.path("repository").asText(null))
    //                                     .build())
    //                             .toList();
    //                 }

    //                 return ChartDetailDto.builder()
    //                         .repositoryName(repositoryName)
    //                         .name(targetChartName)
    //                         .version(latestVersion.path("version").asText())
    //                         .description(latestVersion.path("description").asText(null))
    //                         .appVersion(latestVersion.path("appVersion").asText(null))
    //                         .keywords(keywords)
    //                         .created(latestVersion.path("created").asText(null))
    //                         .maintainers(maintainers)
    //                         .source(latestVersion.path("sources").isArray() && latestVersion.path("sources").size() > 0
    //                                 ? latestVersion.path("sources").get(0).asText(null)
    //                                 : null)
    //                         .home(latestVersion.path("home").asText(null))
    //                         .icon(latestVersion.path("icon").asText(null))
    //                         .dependencies(dependencies)
    //                         .versionHistory(versionHistory)
    //                         .build();
    //             }
    //         }

    //         throw new HelmChartNotFoundException(
    //                 "Chart not found: " + targetChartName + " in repository " + repositoryName);

    //     } catch (Exception e) {
    //         log.error("Failed to parse index.yaml", e);
    //         throw new HelmChartNotFoundException("Failed to parse repository index: " + repositoryName);
    //     }
    // }



    // /**
    //  * Helm install 명령어를 빌드합니다.
    //  */
    // private String buildHelmInstallCommand(HelmRepoEntity repository, String chartName, String releaseName,
    //         String namespace, String version, MultipartFile valuesFile, String kubeconfigPath) {
    //     // 먼저 repository를 추가
    //     String repoAddCommand = helmCommandExecutor.buildHelmRepoAddCommand(repository);

    //     StringBuilder command = new StringBuilder();
    //     command.append(repoAddCommand).append(" && ");
    //     command.append("helm install ")
    //             .append(releaseName)
    //             .append(" ")
    //             .append(repository.getName())
    //             .append("/")
    //             .append(chartName);

    //     // kubeconfig 파일 지정
    //     command.append(" --kubeconfig ").append(kubeconfigPath);

    //     if (namespace != null && !namespace.trim().isEmpty()) {
    //         command.append(" --namespace ").append(namespace)
    //                 .append(" --create-namespace");
    //     }

    //     if (version != null && !version.trim().isEmpty()) {
    //         command.append(" --version ").append(version);
    //     } else {
    //         // 버전이 지정되지 않으면 최신 버전 사용
    //         log.info("No version specified, using latest version");
    //     }

    //     // values 파일이 있으면 사용
    //     if (valuesFile != null && !valuesFile.isEmpty() && valuesFile.getSize() > 0) {
    //         try {
    //             // 임시 파일로 저장
    //             String tempValuesPath = saveValuesFile(valuesFile);
    //             command.append(" --values ").append(tempValuesPath);
    //             log.info("Using values file: {}", tempValuesPath);
    //         } catch (IOException e) {
    //             log.error("Failed to save values file", e);
    //             throw new HelmDeploymentException("Failed to process values file: " + e.getMessage());
    //         }
    //     } else {
    //         log.info("No values file provided, using default values");
    //     }

    //     // 배포 옵션 추가 (atomic 제거하여 타임아웃 방지)
    //     command.append(" --timeout 10m");
        
    //     // TLS 검증 건너뛰기 (자체 서명된 인증서 또는 인증서 없는 클러스터 지원)
    //     command.append(" --insecure-skip-tls-verify");

    //     return command.toString();
    // }

    // /**
    //  * MultipartFile을 임시 파일로 저장합니다.
    //  */
    // private String saveValuesFile(MultipartFile valuesFile) throws IOException {
    //     String tempDir = System.getProperty("java.io.tmpdir");
    //     String fileName = "values-" + System.currentTimeMillis() + ".yaml";
    //     Path tempFile = Paths.get(tempDir, fileName);

    //     Files.write(tempFile, valuesFile.getBytes());
    //     log.info("Saved values file to: {}", tempFile.toString());

    //     return tempFile.toString();
    // }

    // /**
    //  * Helm status 명령어를 빌드합니다.
    //  */
    // private String buildHelmStatusCommand(String releaseName, String namespace, String kubeconfigPath) {
    //     StringBuilder command = new StringBuilder();
    //     command.append("helm status ")
    //             .append(releaseName);

    //     // kubeconfig 파일 지정
    //     command.append(" --kubeconfig ").append(kubeconfigPath);

    //     if (namespace != null && !namespace.trim().isEmpty()) {
    //         command.append(" --namespace ").append(namespace);
    //     }

    //     return command.toString();
    // }

    // private String executeHelmCommand(String command, String kubeconfigPath) throws IOException, InterruptedException {
    //     log.debug("Executing helm command: {}", command);

    //     ProcessBuilder processBuilder = new ProcessBuilder();
    //     processBuilder.command("sh", "-c", command);
    //     processBuilder.redirectErrorStream(true);

    //     // kubeconfig 환경변수 설정
    //     Map<String, String> environment = processBuilder.environment();
    //     environment.put("KUBECONFIG", kubeconfigPath);

    //     Process process = processBuilder.start();

    //     StringBuilder output = new StringBuilder();
    //     try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
    //         String line;
    //         while ((line = reader.readLine()) != null) {
    //             output.append(line).append("\n");
    //         }
    //     }

    //     boolean finished = process.waitFor(60, TimeUnit.SECONDS);
    //     if (!finished) {
    //         process.destroyForcibly();
    //         throw new HelmDeploymentException("Helm command timed out: " + command);
    //     }

    //     int exitCode = process.exitValue();
    //     String commandOutput = output.toString();
        
    //     // 디버깅을 위한 상세 로깅
    //     log.info("Helm command executed. Exit code: {}, Output length: {}", exitCode, commandOutput.length());
    //     log.debug("Helm command output: {}", commandOutput);
        
    //     // exit code와 관계없이 에러 패턴도 확인 (일부 Helm 명령어는 에러가 있어도 exit code 0을 반환할 수 있음)
    //     boolean hasError = exitCode != 0 || 
    //                       commandOutput.contains("Error:") || 
    //                       commandOutput.contains("INSTALLATION FAILED") ||
    //                       commandOutput.contains("FAILED");
        
    //     if (hasError) {
    //         log.error("Helm command error detected. Exit code: {}, Output: {}", exitCode, commandOutput);
            
    //         // 특정 에러 패턴 감지 및 맞춤형 에러 메시지 제공
    //         if (commandOutput.contains("cannot re-use a name that is still in use")) {
    //             throw new HelmDeploymentException(
    //                 "Release name already exists. Please use a different release name or uninstall the existing release first. " +
    //                 "Error details: " + commandOutput);
    //         } else if (commandOutput.contains("tls: failed to verify certificate")) {
    //             throw new HelmDeploymentException(
    //                 "TLS certificate verification failed. Please check cluster certificate configuration. " +
    //                 "Error details: " + commandOutput);
    //         } else if (commandOutput.contains("connection refused") || commandOutput.contains("unable to connect")) {
    //             throw new HelmDeploymentException(
    //                 "Unable to connect to Kubernetes cluster. Please check cluster connectivity. " +
    //                 "Error details: " + commandOutput);
    //         } else {
    //             throw new HelmDeploymentException(
    //                 "Helm command failed with exit code " + exitCode + ": " + commandOutput);
    //         }
    //     }

    //     return output.toString();
    // }

    // private String executeHelmCommandWithoutKubeconfig(String command) throws IOException, InterruptedException {
    //     log.debug("Executing helm command (without kubeconfig): {}", command);

    //     ProcessBuilder processBuilder = new ProcessBuilder();
    //     processBuilder.command("sh", "-c", command);
    //     processBuilder.redirectErrorStream(true);

    //     Process process = processBuilder.start();

    //     StringBuilder output = new StringBuilder();
    //     try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
    //         String line;
    //         while ((line = reader.readLine()) != null) {
    //             output.append(line).append("\n");
    //         }
    //     }

    //     boolean finished = process.waitFor(60, TimeUnit.SECONDS);
    //     if (!finished) {
    //         process.destroyForcibly();
    //         throw new HelmDeploymentException("Helm command timed out: " + command);
    //     }

    //     int exitCode = process.exitValue();
    //     if (exitCode != 0) {
    //         throw new HelmDeploymentException(
    //                 "Helm command failed with exit code " + exitCode + ": " + output.toString());
    //     }

    //     return output.toString();
    // }

    private HttpHeaders createAuthHeaders(HelmRepoEntity repository) {
        HttpHeaders headers = new HttpHeaders();

        if (repository.getUsername() != null && repository.getPassword() != null) {
            String auth = repository.getUsername() + ":" + repository.getPassword();
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            headers.set("Authorization", "Basic " + encodedAuth);
        }

        return headers;
    }

    @Override
    public ChartReleasesResponseDto getReleases(String clusterId, String namespace) {
        log.info("Getting releases for cluster: {}, namespace: {}", clusterId, namespace);

        try {
            ClusterEntity cluster = getCluster(clusterId);

            // kubeconfig 파일 생성
            String kubeconfigPath = createKubeconfigFile(cluster);

            try {
                // Helm list 명령어 실행
                String command = helmCommandExecutor.buildHelmListCommand(namespace, kubeconfigPath);
                String output = helmCommandExecutor.executeHelmCommand(command, kubeconfigPath);

                // 출력 파싱
                List<ChartReleasesResponseDto.ReleaseInfo> releases = chartParser.parseHelmListOutput(output);

                log.info("Successfully retrieved {} releases for cluster: {}", releases.size(), clusterId);

                return ChartReleasesResponseDto.builder()
                        .success(true)
                        .message("Releases retrieved successfully")
                        .releases(releases)
                        .build();

            } finally {
                // 임시 kubeconfig 파일 삭제
                deleteKubeconfigFile(kubeconfigPath);
            }

        } catch (Exception e) {
            log.error("Failed to get releases for cluster: {}", clusterId, e);
            return ChartReleasesResponseDto.builder()
                    .success(false)
                    .message("Failed to retrieve releases: " + e.getMessage())
                    .releases(new ArrayList<>())
                    .build();
        }
    }



}
