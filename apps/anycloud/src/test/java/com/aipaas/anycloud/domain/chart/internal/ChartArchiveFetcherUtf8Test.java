package com.aipaas.anycloud.domain.chart.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.common.error.exception.HelmChartNotFoundException;
import com.aipaas.anycloud.domain.helmrepo.HelmRepoEntity;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * {@link ChartArchiveFetcher} 의 index.yaml UTF-8 디코딩 회귀 테스트.
 *
 * <p>버그 시나리오: ChartMuseum 등 일부 helm repo server 가 response Content-Type 에 charset 를
 * 지정하지 않으면 Spring 의 StringHttpMessageConverter 가 RFC 2616 default 인 ISO-8859-1 로 디코딩.
 * UTF-8 multi-byte 문자 (em-dash {@code —} U+2014 = bytes {@code 0xE2 0x80 0x94}) 가 mojibake 가
 * 되어 SnakeYAML 이 "special characters are not allowed" 로 reject. fix: byte[] 로 받은 후
 * 명시 UTF-8 변환.
 */
class ChartArchiveFetcherUtf8Test extends AbstractUnitTest {

    @Mock
    RestTemplate restTemplate;

    @InjectMocks
    ChartArchiveFetcher fetcher;

    @Test
    void resolveChartUrl_parsesUtf8MultiByteIndex_withoutSpecialCharsError() {
        // em-dash (—, U+2014) 가 chart description 에 들어간 index.yaml.
        // 실제 ChartMuseum 응답에서 흔한 패턴 — prometheus, ingress-nginx 등 chart description 에 종종 포함.
        String indexYaml =
                """
				apiVersion: v1
				entries:
				  test-chart:
				    - apiVersion: v2
				      name: test-chart
				      version: 1.0.0
				      description: a sample chart — with em-dash and 한글
				      urls:
				        - test-chart-1.0.0.tgz
				generated: "2026-05-21T00:00:00Z"
				""";
        byte[] utf8Bytes = indexYaml.getBytes(StandardCharsets.UTF_8);

        // charset 없는 Content-Type → byte[] 로 받아 UTF-8 명시 디코딩. String.class 직접 수신은
        // ISO-8859-1 fallback 으로 em-dash 가 깨지고 yamlMapper.readTree 가
        // "special characters are not allowed" 로 reject.
        when(restTemplate.exchange(
                        eq("http://example.com/repo/index.yaml"),
                        eq(HttpMethod.GET),
                        any(HttpEntity.class),
                        eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(utf8Bytes, HttpStatus.OK));

        HelmRepoEntity repo = HelmRepoEntity.builder()
                .name("test-repo")
                .url("http://example.com/repo")
                .build();

        // resolveChartUrl 은 private — 본 테스트는 fetchEntry 를 통해 간접 검증.
        // 다음 단계 (tgz download) 는 mock 안 했으므로 NPE 또는 다른 exception 으로 떨어짐 —
        // 본 테스트의 통과 기준은 SnakeYAML 의 "special characters" 가 아니라는 것뿐.
        assertThatThrownBy(() -> fetcher.fetchEntry(repo, "test-chart", "1.0.0", "values.yaml"))
                .satisfies(e -> {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    assertThat(msg).doesNotContain("special characters are not allowed");
                    assertThat(msg).doesNotContain("Invalid index.yaml");
                });
    }

    @Test
    void resolveChartUrl_invalidYaml_stillThrowsInvalidIndex() {
        // Sanity check: 정말로 invalid YAML 이면 여전히 "Invalid index.yaml" 응답.
        // fix 가 valid UTF-8 YAML 을 깨지 않으면서도 진짜 invalid 케이스는 캐치하는지 확인.
        byte[] garbage = new byte[] {0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE};

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(garbage, HttpStatus.OK));

        HelmRepoEntity repo = HelmRepoEntity.builder()
                .name("bad-repo")
                .url("http://example.com/bad")
                .build();

        assertThatThrownBy(() -> fetcher.fetchEntry(repo, "any-chart", "1.0.0", "values.yaml"))
                .isInstanceOf(HelmChartNotFoundException.class);
    }
}
