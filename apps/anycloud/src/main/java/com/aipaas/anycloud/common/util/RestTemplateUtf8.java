package com.aipaas.anycloud.common.util;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * safe HTTP response 읽기 헬퍼.
 *
 * <p>{@link RestTemplate#exchange(String, HttpMethod, HttpEntity, Class)} 가 {@code String.class}
 * 로 호출되면 Spring 의 {@code StringHttpMessageConverter} 가 response 의 {@code Content-Type} 헤더
 * 에서 charset 을 찾고, 없으면 RFC 2616 default 인 -1 로 디코딩. multi-byte 문자
 * (em-dash {@code —} U+2014 = bytes {@code 0xE2 0x80 0x94}, 한글 등) 가 mojibake 가 되어
 * 후속 파서 (SnakeYAML / Jackson) 가 reject.
 *
 * <p>본 helper 는 {@code byte[]} 로 받은 후 명시적으로 디코딩 — server 의 Content-Type 헤더
 * 와 무관하게 항상 로 해석.
 *
 * <p><b>적용 권장 위치</b>:
 * <ul>
 *   <li>charset 미지정 server (ChartMuseum, OpenStack 일부 deployment, 자체 dev 서비스 등)</li>
 *   <li>YAML / SnakeYAML 으로 파싱하는 응답 (control char 검증 strict)</li>
 *   <li>multi-byte 문자 (em-dash / 한글 / 일본어 / 중국어) 가 포함될 가능성 있는 응답</li>
 * </ul>
 *
 * <p><b>이미 적용된 곳</b>:
 * <ul>
 *   <li>{@code ChartMetadataServiceImpl#fetchIndexYaml}</li>
 *   <li>{@code ChartArchiveFetcher#resolveChartUrl}</li>
 *   <li>{@code OpenStackVmOptionsProvider} — KKK 의 defensive fix</li>
 * </ul>
 *
 * <p><b>미적용 (안전 확인됨)</b>: OCI / GCP / Azure / DigitalOcean — public cloud API 모두
 * {@code Content-Type: application/json; charset=utf-8} 명시. 향후 새 provider 추가 시 본 helper
 * 사용 권장.
 */
public final class RestTemplateUtf8 {

    private RestTemplateUtf8() {
        // static utility
    }

    /**
     * 안전한 GET 응답 String 반환. response 의 status 또는 body null 처리는 caller 책임.
     *
     * @return 디코딩된 응답 body. body 가 null 이면 빈 문자열.
     */
    public static ResponseEntity<String> getAsUtf8String(RestTemplate restTemplate, String url, HttpHeaders headers) {
        ResponseEntity<byte[]> raw =
                restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        String body = raw.getBody() == null ? "" : new String(raw.getBody(), StandardCharsets.UTF_8);
        return ResponseEntity.status(raw.getStatusCode())
                .headers(raw.getHeaders())
                .body(body);
    }

    /**
     * 안전한 generic HTTP method 호출. body 가 필요한 PUT/POST 등에도 사용 가능.
     */
    public static ResponseEntity<String> exchangeAsUtf8String(
            RestTemplate restTemplate, String url, HttpMethod method, HttpEntity<?> requestEntity) {
        ResponseEntity<byte[]> raw = restTemplate.exchange(url, method, requestEntity, byte[].class);
        String body = raw.getBody() == null ? "" : new String(raw.getBody(), StandardCharsets.UTF_8);
        return ResponseEntity.status(raw.getStatusCode())
                .headers(raw.getHeaders())
                .body(body);
    }
}
