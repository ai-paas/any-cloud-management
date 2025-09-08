package com.aipaas.anycloud.service.Impl;

import com.aipaas.anycloud.error.exception.HelmChartNotFoundException;
import com.aipaas.anycloud.error.exception.HelmDeploymentException;
import com.aipaas.anycloud.error.exception.HelmRepositoryNotFoundException;
import com.aipaas.anycloud.model.dto.request.ChartDeployDto;
import com.aipaas.anycloud.model.dto.response.*;
import com.aipaas.anycloud.model.entity.HelmRepoEntity;
import com.aipaas.anycloud.service.ChartService;
import com.aipaas.anycloud.service.HelmRepoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
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
            String valuesContent = executeHelmCommand(command);

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
            String readmeContent = executeHelmCommand(command);

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
        log.info("Deploying chart: {}/{} as release: {}", repositoryName, chartName, deployDto.getReleaseName());

        HelmRepoEntity repository = getRepository(repositoryName);

        try {
            // Helm CLI를 사용하여 차트 배포
            String command = buildHelmInstallCommand(repository, chartName, deployDto);
            String output = executeHelmCommand(command);

            return ChartDeployResponseDto.builder()
                .success(true)
                .releaseName(deployDto.getReleaseName())
                .namespace(deployDto.getNamespace() != null ? deployDto.getNamespace() : "default")
                .chartVersion(deployDto.getVersion())
                .status("deployed")
                .message("Release " + deployDto.getReleaseName() + " has been deployed successfully")
                .output(output)
                .build();

        } catch (Exception e) {
            log.error("Failed to deploy chart: {}/{}", repositoryName, chartName, e);
            throw new HelmDeploymentException("Failed to deploy chart: " + repositoryName + "/" + chartName + ". " + e.getMessage());
        }
    }

    private HelmRepoEntity getRepository(String repositoryName) {
        if (!helmRepoService.isHelmExist(repositoryName)) {
            throw new HelmRepositoryNotFoundException(repositoryName);
        }
        return helmRepoService.getHelmRepo(repositoryName);
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
    private String buildHelmInstallCommand(HelmRepoEntity repository, String chartName, ChartDeployDto deployDto) {
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
            for (Map.Entry<String, Object> entry : deployDto.getValues().entrySet()) {
                command.append(" --set ")
                       .append(entry.getKey())
                       .append("=")
                       .append(entry.getValue());
            }
        }

        return command.toString();
    }

    private String executeHelmCommand(String command) throws IOException, InterruptedException {
        log.debug("Executing helm command: {}", command);

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
