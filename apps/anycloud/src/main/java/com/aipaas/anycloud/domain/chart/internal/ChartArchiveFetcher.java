package com.aipaas.anycloud.domain.chart.internal;

import com.aipaas.anycloud.common.error.exception.HelmChartNotFoundException;
import com.aipaas.anycloud.domain.helmrepo.HelmRepoEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Helm chart {@code .tgz} 아카이브에서 단일 파일을 in-process 로 직접 추출.
 *
 * <ol>
 *   <li>{@code index.yaml} 을 HTTP GET 해 차트 entry 의 {@code urls[]} resolve</li>
 *   <li>해당 .tgz 를 HTTP GET (basic auth 헤더 동일 적용)</li>
 *   <li>gzip 해제 → tar entry 순회 → 일치하는 archive path 의 content 반환</li>
 * </ol>
 *
 * <p>helm CLI subprocess 의존 없음 — pure JVM 호출이라 동시 호출이 process race 없이 안전.
 *
 * <h3>Tar archive path convention</h3>
 * Helm chart .tgz 는 항상 root 디렉토리 1개 안에 packed: {@code <chartName>/values.yaml},
 * {@code <chartName>/README.md} 등. caller 는 chartName 만 알면 됨.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChartArchiveFetcher {

    /** 단일 archive entry 의 최대 크기 — chart values / README 가 8MB 넘으면 비정상. */
    private static final int MAX_ENTRY_BYTES = 8 * 1024 * 1024;

    /** Chart .tgz 자체의 최대 크기 — 큰 chart 도 일반적으로 5MB. 50MB cap 으로 안전. */
    private static final int MAX_ARCHIVE_BYTES = 50 * 1024 * 1024;

    private final RestTemplate restTemplate;
    private final YAMLMapper yamlMapper = new YAMLMapper();

    /**
     * 지정 chart 의 archive 안에서 {@code archivePath} 파일을 텍스트로 추출.
     *
     * @param repository    Helm repo entity (URL + auth).
     * @param chartName     chart 이름 (e.g. "kube-prometheus-stack").
     * @param version       chart version. null/blank 면 최신.
     * @param archivePath   {@code <chartName>/...} 형식의 archive 내부 경로 (e.g. "values.yaml" 또는 "README.md")
     * @return file content (없으면 {@link HelmChartNotFoundException}).
     */
    public String fetchEntry(HelmRepoEntity repository, String chartName, String version, String archivePath) {
        String chartUrl = resolveChartUrl(repository, chartName, version);
        byte[] tgz = downloadArchive(repository, chartUrl);

        String fullArchivePath = chartName + "/" + archivePath;
        String content = extractEntry(tgz, fullArchivePath, chartName, archivePath);
        if (content == null) {
            throw new HelmChartNotFoundException("Chart " + repository.getName() + "/" + chartName + " " + version
                    + " has no entry at " + archivePath);
        }
        return content;
    }

    /**
     * chart {@code .tgz} 전체 archive 를 byte[] 로 반환. backend 가 agent 에 push 할
     * 용도 (helm install 시 agent 가 chartmuseum 에 직접 접근 못하는 air-gapped / 사내망 케이스).
     *
     * <p>{@link #fetchEntry} 와 동일한 internal download path 재사용. {@link #MAX_ARCHIVE_BYTES}
     * (50MB) cap 이 그대로 적용 — 비정상 크기 chart 거부.
     *
     * @return chart .tgz 의 raw bytes
     * @throws com.aipaas.anycloud.common.error.exception.HelmChartNotFoundException repo / chart /
     *         version 매칭 실패 또는 download 실패
     */
    public byte[] fetchArchive(HelmRepoEntity repository, String chartName, String version) {
        String chartUrl = resolveChartUrl(repository, chartName, version);
        return downloadArchive(repository, chartUrl);
    }

    /**
     * index.yaml 에서 chart 의 .tgz URL 을 찾는다. version null/blank 면 entries[0] (Helm 의 latest).
     * URL 이 relative 면 repository URL 기준으로 resolve.
     *
     * <p><b>중요</b>: index.yaml 을 {@code byte[]} 로 받은 후 명시적으로 디코딩.
     * RestTemplate 의 StringHttpMessageConverter 가 response Content-Type 에 {@code charset=} 가
     * 없을 때 -1 로 디코딩하는 RFC 2616 default 동작 때문 — ChartMuseum 등 일부 server 는
     * Content-Type 에 charset 지정 안 함. multi-byte 문자 (예: em-dash {@code —}
     * U+2014 = {@code 0xE2 0x80 0x94}) 가 mojibake 가 되어 SnakeYAML 이
     * "special characters are not allowed" 로 reject 하는 회귀 회피.
     * {@link com.aipaas.anycloud.domain.chart.internal.ChartMetadataServiceImpl#fetchIndexYaml} 와 동일 패턴.
     */
    private String resolveChartUrl(HelmRepoEntity repository, String chartName, String version) {
        String indexUrl = repository.getUrl().endsWith("/")
                ? repository.getUrl() + "index.yaml"
                : repository.getUrl() + "/index.yaml";

        HttpHeaders headers = authHeaders(repository);
        ResponseEntity<byte[]> resp =
                restTemplate.exchange(indexUrl, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            throw new HelmChartNotFoundException("Failed to fetch index.yaml from " + repository.getName() + " (HTTP "
                    + resp.getStatusCode().value() + ")");
        }
        String body = new String(resp.getBody(), StandardCharsets.UTF_8);

        JsonNode root;
        try {
            root = yamlMapper.readTree(body);
        } catch (IOException e) {
            throw new HelmChartNotFoundException(
                    "Invalid index.yaml from " + repository.getName() + ": " + e.getMessage());
        }
        JsonNode entries = root.path("entries").path(chartName);
        if (!entries.isArray() || entries.isEmpty()) {
            throw new HelmChartNotFoundException(repository.getName(), chartName);
        }
        JsonNode selected = null;
        if (version != null && !version.isBlank()) {
            for (JsonNode v : entries) {
                if (version.equals(v.path("version").asText())) {
                    selected = v;
                    break;
                }
            }
            if (selected == null) {
                throw new HelmChartNotFoundException("Chart version not found: " + version + " for " + chartName);
            }
        } else {
            selected = entries.get(0); // index.yaml 은 latest first
        }
        JsonNode urls = selected.path("urls");
        if (!urls.isArray() || urls.isEmpty()) {
            throw new HelmChartNotFoundException("Chart " + chartName + " " + version + " has no urls[] in index.yaml");
        }
        String url = urls.get(0).asText();
        return absolutize(url, repository.getUrl());
    }

    /**
     * Relative URL ({@code mychart-1.0.0.tgz}) 을 repository base URL 기준으로 절대 URL 로 변환.
     * 이미 절대 URL 이면 그대로.
     */
    private static String absolutize(String url, String repoBaseUrl) {
        try {
            URI uri = new URI(url);
            if (uri.isAbsolute()) {
                return url;
            }
        } catch (URISyntaxException ignored) {
            // fall through
        }
        String base = repoBaseUrl.endsWith("/") ? repoBaseUrl : repoBaseUrl + "/";
        try {
            return new URI(base).resolve(url).toString();
        } catch (URISyntaxException e) {
            // Best-effort fallback — simple concat.
            return base + url;
        }
    }

    private byte[] downloadArchive(HelmRepoEntity repository, String chartUrl) {
        HttpHeaders headers = authHeaders(repository);
        log.debug("Fetching chart archive: {}", chartUrl);
        ResponseEntity<byte[]> resp =
                restTemplate.exchange(chartUrl, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            throw new HelmChartNotFoundException("Failed to download chart archive " + chartUrl + " (HTTP "
                    + resp.getStatusCode().value() + ")");
        }
        if (resp.getBody().length > MAX_ARCHIVE_BYTES) {
            throw new HelmChartNotFoundException("Chart archive too large (" + resp.getBody().length + " bytes, cap "
                    + MAX_ARCHIVE_BYTES + "): " + chartUrl);
        }
        return resp.getBody();
    }

    /**
     * tgz 바이트에서 {@code fullArchivePath} 파일의 content 를 텍스트로 반환. 없으면 null.
     *
     * <p>대 / 소문자 보존 매칭. 동일 이름이 여러 번 나타나면 처음 발견된 것을 반환 (helm chart 는 normally
     * 중복 entry 없음).
     */
    private static String extractEntry(byte[] tgz, String fullArchivePath, String chartName, String archivePath) {
        try (TarArchiveInputStream tar =
                new TarArchiveInputStream(new GZIPInputStream(new ByteArrayInputStream(tgz)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (matchEntry(entry.getName(), fullArchivePath)) {
                    if (entry.getSize() > MAX_ENTRY_BYTES) {
                        throw new HelmChartNotFoundException("Chart entry " + archivePath + " too large ("
                                + entry.getSize() + " bytes, cap " + MAX_ENTRY_BYTES + ")");
                    }
                    byte[] buf = tar.readNBytes((int) entry.getSize());
                    return new String(buf, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new HelmChartNotFoundException(
                    "Failed to read chart archive for " + chartName + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * tar entry name 매칭. helm chart 의 tar entry 이름은 항상 {@code <chartName>/...} prefix 라
     * 정확 일치, 또는 일부 packager 가 {@code ./<chartName>/...} prefix 를 쓰는 경우도 허용.
     */
    private static boolean matchEntry(String entryName, String expected) {
        if (entryName.equals(expected)) {
            return true;
        }
        if (entryName.startsWith("./") && entryName.substring(2).equals(expected)) {
            return true;
        }
        return false;
    }

    /** Repository 의 username/password 가 있으면 Basic auth 헤더 부착. */
    private static HttpHeaders authHeaders(HelmRepoEntity repository) {
        HttpHeaders headers = new HttpHeaders();
        if (repository.getUsername() != null && repository.getPassword() != null) {
            String token = repository.getUsername() + ":" + repository.getPassword();
            String encoded = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
            headers.set("Authorization", "Basic " + encoded);
        }
        return headers;
    }
}
