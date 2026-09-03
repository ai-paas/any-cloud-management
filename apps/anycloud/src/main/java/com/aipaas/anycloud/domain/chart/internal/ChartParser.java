package com.aipaas.anycloud.domain.chart.internal;

import com.aipaas.anycloud.common.error.exception.HelmChartNotFoundException;
import com.aipaas.anycloud.domain.chart.api.response.ChartDetailResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartListResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

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

    /**
     * Jackson YAMLFactory 는 내부적으로 SnakeYAML 의 ScannerImpl 을 사용. classpath 에 fabric8
     * 이 끌어오는 {@code org.snakeyaml:snakeyaml-engine} (YAML 1.2 strict, 별개 namespace) 와 충돌이
     * 의심되는 케이스에서 plain ASCII YAML 도 "special characters at line 1, column 1" 으로 reject 되는
     * regression 관찰됨 (ChartMuseum index.yaml 파싱 실패).
     *
     * <p>워크어라운드: Spring Boot 가 제공하는 {@code org.yaml:snakeyaml} 2.2 를 직접 호출 → Map/List
     * 로 받은 뒤 Jackson 으로 JsonNode 변환. 결과는 동일하지만 reserved-character check 우회.
     */
    private static final Yaml SNAKE_YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /** YAML String → JsonNode. Jackson YAMLFactory 우회 — 위 {@link #SNAKE_YAML} doc 참조. */
    private JsonNode parseYamlToJsonNode(String yamlContent) {
        Object parsed = SNAKE_YAML.load(yamlContent);
        return jsonMapper.valueToTree(parsed);
    }

    /**
     * index.yaml 내용을 파싱하여 차트 목록을 생성합니다.
     */
    public ChartListResponse parseIndexYaml(String repositoryName, String indexContent) {
        try {
            JsonNode rootNode = parseYamlToJsonNode(indexContent);
            JsonNode entriesNode = rootNode.get("entries");

            List<ChartListResponse.ChartInfo> charts = new ArrayList<>();

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

                        // versionHistory 처리
                        List<ChartDetailResponse.VersionHistory> versionHistory = StreamSupport.stream(
                                        chartVersions.spliterator(), false)
                                .map(v -> ChartDetailResponse.VersionHistory.builder()
                                        .version(v.path("version").asText())
                                        .appVersion(v.path("appVersion").asText())
                                        .created(v.path("created").asText(null))
                                        .build())
                                .toList();

                        charts.add(ChartListResponse.ChartInfo.builder()
                                .name(chartName)
                                .version(latestVersion.path("version").asText())
                                .description(latestVersion.path("description").asText(null))
                                .appVersion(latestVersion.path("appVersion").asText(null))
                                .keywords(keywords)
                                .icon(latestVersion.path("icon").asText(null))
                                .created(latestVersion.path("created").asText(null))
                                .versionHistory(versionHistory)
                                .build());
                    }
                });
            }

            log.info("Parsed {} charts from repository: {}", charts.size(), repositoryName);

            return ChartListResponse.builder()
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
     *
     * @param repositoryName  Repository 이름
     * @param targetChartName 차트 이름
     * @param targetVersion   차트 버전 (null일 경우 최신 버전)
     * @param indexContent    index.yaml 내용
     * @return 차트 상세 정보
     */
    public ChartDetailResponse parseChartDetail(
            String repositoryName, String targetChartName, String targetVersion, String indexContent) {
        try {
            JsonNode rootNode = parseYamlToJsonNode(indexContent);
            JsonNode entriesNode = rootNode.get("entries");

            if (entriesNode != null && entriesNode.has(targetChartName)) {
                JsonNode chartVersions = entriesNode.get(targetChartName);
                if (chartVersions.isArray() && chartVersions.size() > 0) {
                    // 버전이 지정된 경우 해당 버전을 찾고, 없으면 최신 버전 사용
                    JsonNode selectedVersion = null;
                    if (targetVersion != null && !targetVersion.trim().isEmpty()) {
                        for (JsonNode versionNode : chartVersions) {
                            if (targetVersion.equals(versionNode.path("version").asText())) {
                                selectedVersion = versionNode;
                                break;
                            }
                        }
                        if (selectedVersion == null) {
                            throw new HelmChartNotFoundException(
                                    "Chart version not found: " + targetVersion + " for chart: " + targetChartName);
                        }
                    } else {
                        // 최신 버전 정보 사용 (첫 번째 요소)
                        selectedVersion = chartVersions.get(0);
                    }

                    // keywords 처리
                    JsonNode keywordsNode = selectedVersion.path("keywords");
                    String[] keywords = null;
                    if (keywordsNode.isArray()) {
                        keywords = StreamSupport.stream(keywordsNode.spliterator(), false)
                                .map(JsonNode::asText)
                                .toArray(String[]::new);
                    }

                    // maintainers 처리
                    JsonNode maintainersNode = selectedVersion.path("maintainers");
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
                    JsonNode dependenciesNode = selectedVersion.path("dependencies");
                    List<ChartDetailResponse.Dependency> dependencies = null;
                    if (dependenciesNode.isArray()) {
                        dependencies = StreamSupport.stream(dependenciesNode.spliterator(), false)
                                .map(dep -> ChartDetailResponse.Dependency.builder()
                                        .name(dep.path("name").asText(null))
                                        .version(dep.path("version").asText(null))
                                        .repository(dep.path("repository").asText(null))
                                        .build())
                                .toList();
                    }

                    // versionHistory 처리 (모든 버전 정보)
                    List<ChartDetailResponse.VersionHistory> versionHistory = StreamSupport.stream(
                                    chartVersions.spliterator(), false)
                            .map(v -> ChartDetailResponse.VersionHistory.builder()
                                    .version(v.path("version").asText())
                                    .appVersion(v.path("appVersion").asText())
                                    .created(v.path("created").asText(null))
                                    .build())
                            .toList();

                    return ChartDetailResponse.builder()
                            .repositoryName(repositoryName)
                            .name(targetChartName)
                            .version(selectedVersion.path("version").asText())
                            .description(selectedVersion.path("description").asText(null))
                            .appVersion(selectedVersion.path("appVersion").asText(null))
                            .keywords(keywords)
                            .created(selectedVersion.path("created").asText(null))
                            .maintainers(maintainers)
                            .source(
                                    selectedVersion.path("sources").isArray()
                                                    && selectedVersion
                                                                    .path("sources")
                                                                    .size()
                                                            > 0
                                            ? selectedVersion
                                                    .path("sources")
                                                    .get(0)
                                                    .asText(null)
                                            : null)
                            .home(selectedVersion.path("home").asText(null))
                            .icon(selectedVersion.path("icon").asText(null))
                            .dependencies(dependencies)
                            .versionHistory(versionHistory)
                            .build();
                }
            }

            throw new HelmChartNotFoundException(
                    "Chart not found: " + targetChartName + " in repository: " + repositoryName);

        } catch (HelmChartNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse chart detail for: {}/{}", repositoryName, targetChartName, e);
            throw new HelmChartNotFoundException("Failed to parse chart detail: " + targetChartName);
        }
    }

    // parseHelmListOutput / parseHelmStatusOutput / parseHistoryJson 모두 제거됨
    // agent 의 LIST_HELM_RELEASES / GET_HELM_RELEASE_STATUS / GET_HELM_RELEASE_HISTORY 가
    // 이미 구조화된 결과 (struct field) 를 반환하므로 raw CLI 출력 파싱이 불필요.

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
