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
import com.aipaas.anycloud.service.util.HelmReleaseScanner;
import com.aipaas.anycloud.service.util.ChartValidator;
import com.aipaas.anycloud.service.util.ChartParser;
import com.aipaas.anycloud.service.util.DeploymentOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
    private final HelmReleaseScanner helmReleaseScanner;

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
    public ChartDetailDto getChartDetail(String repositoryName, String chartName, String version) {
        log.info("Getting chart detail for repository: {}, chart: {}, version: {}", repositoryName, chartName, version);

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
                return chartParser.parseChartDetail(repositoryName, chartName, version, response.getBody());
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
                String command = helmCommandExecutor.buildHelmStatusCommand(releaseName, targetNamespace,
                        kubeconfigPath);
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

    @Override
    public List<? extends HasMetadata> getHelmResources(String clusterName, String namespace, String releaseName) {
        ClusterEntity cluster = clusterService.getCluster(clusterName);
        return helmReleaseScanner.scanReleaseResources(cluster, namespace, releaseName);
    }

}
