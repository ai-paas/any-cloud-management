package com.aipaas.anycloud.domain.chart.internal;

import com.aipaas.anycloud.common.error.exception.HelmChartNotFoundException;
import com.aipaas.anycloud.common.error.exception.HelmRepositoryNotFoundException;
import com.aipaas.anycloud.domain.chart.ChartMetadataService;
import com.aipaas.anycloud.domain.chart.api.response.ChartDetailResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartListResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartReadmeResponse;
import com.aipaas.anycloud.domain.chart.api.response.ChartValuesResponse;
import com.aipaas.anycloud.domain.helmrepo.HelmRepoEntity;
import com.aipaas.anycloud.domain.helmrepo.HelmRepoService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Helm chart 메타데이터 조회 구현 — metadata 4 method + helper.
 *
 * <p>외부 helm repo HTTP 호출 ({@link RestTemplate}) + chart archive fetch
 * ({@link ChartArchiveFetcher}) + parse ({@link ChartParser}) + repo lookup ({@link HelmRepoService}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChartMetadataServiceImpl implements ChartMetadataService {

    private final HelmRepoService helmRepoService;
    private final RestTemplate restTemplate;
    /** Chart 메타데이터를 in-process 로 추출 (helm CLI subprocess 대체). */
    private final ChartArchiveFetcher chartArchiveFetcher;

    private final ChartParser chartParser;

    @Override
    public ChartListResponse getChartList(String repositoryName) {
        log.info("Getting chart list for repository: {}", repositoryName);

        HelmRepoEntity repository = getRepository(repositoryName);
        try {
            String indexUrl = repository.getUrl().endsWith("/")
                    ? repository.getUrl() + "index.yaml"
                    : repository.getUrl() + "/index.yaml";
            String body = fetchIndexYaml(indexUrl, repository);
            return chartParser.parseIndexYaml(repositoryName, body);
        } catch (HelmChartNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error(
                    "Failed to get chart list for repository: {} from URL: {}", repositoryName, repository.getUrl(), e);
            throw new HelmChartNotFoundException(
                    "Failed to fetch charts from repository: " + repositoryName + " - " + e.getMessage());
        }
    }

    @Override
    public ChartDetailResponse getChartDetail(String repositoryName, String chartName, String version) {
        log.info("Getting chart detail for repository: {}, chart: {}, version: {}", repositoryName, chartName, version);

        HelmRepoEntity repository = getRepository(repositoryName);
        try {
            String indexUrl = repository.getUrl().endsWith("/")
                    ? repository.getUrl() + "index.yaml"
                    : repository.getUrl() + "/index.yaml";
            // decoding 강제 — getChartList 와 동일 회피 (ChartMuseum charset 미명시).
            String body = fetchIndexYaml(indexUrl, repository);
            return chartParser.parseChartDetail(repositoryName, chartName, version, body);

        } catch (HelmChartNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error(
                    "Failed to get chart detail for repository: {}, chart: {} from URL: {}",
                    repositoryName,
                    chartName,
                    repository.getUrl(),
                    e);
            throw new HelmChartNotFoundException(
                    "Failed to fetch chart detail: " + repositoryName + "/" + chartName + " - " + e.getMessage());
        }
    }

    @Override
    public ChartValuesResponse getChartValues(String repositoryName, String chartName, String version) {
        log.info("Getting values for chart: {}/{}, version: {}", repositoryName, chartName, version);
        HelmRepoEntity repository = getRepository(repositoryName);
        String valuesContent = chartArchiveFetcher.fetchEntry(repository, chartName, version, "values.yaml");
        return ChartValuesResponse.builder()
                .repositoryName(repositoryName)
                .chartName(chartName)
                .version(version)
                .valuesContent(valuesContent)
                .build();
    }

    @Override
    public ChartReadmeResponse getChartReadme(String repositoryName, String chartName, String version) {
        log.info("Getting README for chart: {}/{}, version: {}", repositoryName, chartName, version);
        HelmRepoEntity repository = getRepository(repositoryName);
        String readmeContent = chartArchiveFetcher.fetchEntry(repository, chartName, version, "README.md");
        return ChartReadmeResponse.builder()
                .repositoryName(repositoryName)
                .chartName(chartName)
                .version(version)
                .readmeContent(readmeContent)
                .build();
    }

    // =================== Helpers ===================

    private HelmRepoEntity getRepository(String repositoryName) {
        try {
            return helmRepoService.getHelmRepoEntity(repositoryName);
        } catch (Exception e) {
            throw new HelmRepositoryNotFoundException(repositoryName);
        }
    }

    /**
     * Helm repository 의 {@code index.yaml} 을 String 으로 download.
     *
     * <p>{@code byte[]} 로 받은 후 로 명시 변환 — RestTemplate 의 StringHttpMessageConverter 가
     * response Content-Type 에 charset 가 없으면 default -1 로 디코딩하는 RFC 2616 동작으로
     * 인해 multi-byte 문자 (예: ChartMuseum 의 em-dash {@code —} U+2014 = {@code 0xE2 0x80 0x94})
     * 가 깨져 SnakeYAML 이 "special characters are not allowed" 로 reject 하던 버그 회피.
     */
    private String fetchIndexYaml(String indexUrl, HelmRepoEntity repository) {
        HttpHeaders headers = createAuthHeaders(repository);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<byte[]> response = restTemplate.exchange(indexUrl, HttpMethod.GET, entity, byte[].class);

        if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
            throw new HelmChartNotFoundException(
                    "Unable to fetch index.yaml from " + indexUrl + " (HTTP " + response.getStatusCode() + ")");
        }
        byte[] raw = response.getBody();
        String body = new String(raw, StandardCharsets.UTF_8);
        log.info(
                "fetchIndexYaml: repo_url={}, bytes={}, body-prefix={}",
                repository.getUrl(),
                raw.length,
                body.substring(0, Math.min(60, body.length())).replace('\n', ' '));
        return body;
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
