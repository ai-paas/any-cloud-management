package com.aipaas.anycloud.service.util;

import com.aipaas.anycloud.error.exception.HelmChartNotFoundException;
import com.aipaas.anycloud.model.dto.response.ChartDetailDto;
import com.aipaas.anycloud.model.dto.response.ChartListDto;
import com.aipaas.anycloud.model.dto.response.ChartReleasesResponseDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * <pre>
 * ClassName : ChartParser
 * Type : class
 * Description : YAML/JSON 파싱을 담당하는 유틸리티 클래스입니다.
 * Related : ChartServiceImpl
 * </pre>
 */
@Slf4j
@Component
public class ChartParser {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * index.yaml 내용을 파싱하여 차트 목록을 생성합니다.
     */
    public ChartListDto parseIndexYaml(String repositoryName, String indexContent) {
        try {
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

                        charts.add(ChartListDto.ChartInfo.builder()
                                .name(chartName)
                                .version(latestVersion.path("version").asText())
                                .description(latestVersion.path("description").asText(null))
                                .appVersion(latestVersion.path("appVersion").asText(null))
                                .keywords(keywords)
                                .icon(latestVersion.path("icon").asText(null))
                                .created(latestVersion.path("created").asText(null))
                                .build());
                    }
                });
            }

            log.info("Parsed {} charts from repository: {}", charts.size(), repositoryName);

            return ChartListDto.builder()
                    .repositoryName(repositoryName)
                    .charts(charts)
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse index.yaml for repository: {}", repositoryName, e);
            throw new HelmChartNotFoundException("Failed to parse repository index: " + repositoryName);
        }
    }

    /**
     * index.yaml에서 특정 차트의 상세 정보를 파싱합니다.
     */
    public ChartDetailDto parseChartDetail(String repositoryName, String targetChartName, String indexContent) {
        try {
            JsonNode rootNode = yamlMapper.readTree(indexContent);
            JsonNode entriesNode = rootNode.get("entries");

            if (entriesNode != null && entriesNode.has(targetChartName)) {
                JsonNode chartVersions = entriesNode.get(targetChartName);
                if (chartVersions.isArray() && chartVersions.size() > 0) {
                    // 최신 버전 정보 사용
                    JsonNode latestVersion = chartVersions.get(0);

                    // keywords 처리
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
                    
                    // versionHistory 처리
                    // JsonNode versionHistoryNode = latestVersion.path("versionHistory");
                    List<ChartDetailDto.VersionHistory> versionHistory = StreamSupport.stream(chartVersions.spliterator(), false)
                            .map(v -> ChartDetailDto.VersionHistory.builder()
                                    .version(v.path("version").asText())
                                    .appVersion(v.path("appVersion").asText())
                                    .created(v.path("created").asText(null))
                                    .build())
                            .toList();
    

                    return ChartDetailDto.builder()
                            .repositoryName(repositoryName)
                            .name(targetChartName)
                            .version(latestVersion.path("version").asText())
                            .description(latestVersion.path("description").asText(null))
                            .appVersion(latestVersion.path("appVersion").asText(null))
                            .keywords(keywords)
                            .created(latestVersion.path("created").asText(null))
                            .maintainers(maintainers)
                            .source(latestVersion.path("sources").isArray() &&
                                    latestVersion.path("sources").size() > 0 ?
                                    latestVersion.path("sources").get(0).asText(null) : null)
                            .home(latestVersion.path("home").asText(null))
                            .icon(latestVersion.path("icon").asText(null))
                            .dependencies(dependencies)
                            .versionHistory(versionHistory)
                            .build();
                }
            }

            throw new HelmChartNotFoundException("Chart not found: " + targetChartName + " in repository: " + repositoryName);

        } catch (HelmChartNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse chart detail for: {}/{}", repositoryName, targetChartName, e);
            throw new HelmChartNotFoundException("Failed to parse chart detail: " + targetChartName);
        }
    }

    /**
     * Helm list 출력을 파싱하여 릴리즈 목록을 생성합니다.
     */
    public List<ChartReleasesResponseDto.ReleaseInfo> parseHelmListOutput(String output) {
        List<ChartReleasesResponseDto.ReleaseInfo> releases = new ArrayList<>();

        try {
            JsonNode rootNode = jsonMapper.readTree(output);

            if (rootNode.isArray()) {
                for (JsonNode releaseNode : rootNode) {
                    releases.add(ChartReleasesResponseDto.ReleaseInfo.builder()
                            .name(releaseNode.path("name").asText())
                            .namespace(releaseNode.path("namespace").asText())
                            .revision(releaseNode.path("revision").asText())
                            .updated(releaseNode.path("updated").asText())
                            .status(releaseNode.path("status").asText())
                            .chart(releaseNode.path("chart").asText())
                            // .appVersion(releaseNode.path("app_version").asText())
                            .build());
                }
            }

            log.debug("Parsed {} releases from helm list output", releases.size());

        } catch (Exception e) {
            log.error("Failed to parse helm list output", e);
            // 파싱 실패 시 빈 리스트 반환 (전체 기능 중단 방지)
        }

        return releases;
    }

    /**
     * Helm status 출력을 파싱하여 상태 정보를 추출합니다.
     */
    public String parseHelmStatusOutput(String output) {
        if (output == null || output.trim().isEmpty()) {
            return "UNKNOWN";
        }

        try {
            // 상태 정보 추출 로직
            String[] lines = output.split("\n");
            for (String line : lines) {
                if (line.startsWith("STATUS:")) {
                    String status = line.substring("STATUS:".length()).trim();
                    log.debug("Extracted status: {}", status);
                    return status;
                }
            }

            // STATUS: 라인을 찾지 못한 경우 전체 출력에서 상태 키워드 검색
            String lowerOutput = output.toLowerCase();
            if (lowerOutput.contains("deployed")) {
                return "deployed";
            } else if (lowerOutput.contains("failed")) {
                return "failed";
            } else if (lowerOutput.contains("pending-install")) {
                return "pending-install";
            } else if (lowerOutput.contains("pending-upgrade")) {
                return "pending-upgrade";
            } else if (lowerOutput.contains("pending-rollback")) {
                return "pending-rollback";
            }

            log.warn("Could not determine status from helm output: {}", output);
            return "UNKNOWN";

        } catch (Exception e) {
            log.error("Failed to parse helm status output", e);
            return "UNKNOWN";
        }
    }

    /**
     * YAML 콘텐츠를 JSON으로 변환합니다.
     */
    public String yamlToJson(String yamlContent) {
        try {
            JsonNode node = yamlMapper.readTree(yamlContent);
            return jsonMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.error("Failed to convert YAML to JSON", e);
            throw new RuntimeException("YAML to JSON conversion failed", e);
        }
    }

    /**
     * JSON 콘텐츠를 YAML로 변환합니다.
     */
    public String jsonToYaml(String jsonContent) {
        try {
            JsonNode node = jsonMapper.readTree(jsonContent);
            return yamlMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.error("Failed to convert JSON to YAML", e);
            throw new RuntimeException("JSON to YAML conversion failed", e);
        }
    }

    /**
     * JSON 문자열이 유효한지 검증합니다.
     */
    public boolean isValidJson(String jsonString) {
        try {
            jsonMapper.readTree(jsonString);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * YAML 문자열이 유효한지 검증합니다.
     */
    public boolean isValidYaml(String yamlString) {
        try {
            yamlMapper.readTree(yamlString);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
