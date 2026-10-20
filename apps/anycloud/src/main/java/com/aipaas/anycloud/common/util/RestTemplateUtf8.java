package com.aipaas.anycloud.common.util;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * 응답을 {@code byte[]} 로 받아 UTF-8 로 직접 디코딩합니다.
 *
 * <p>{@code String.class} 로 받으면 {@code StringHttpMessageConverter} 가 Content-Type 의 charset
 * 을 보고, 없으면 ISO-8859-1 로 읽습니다. 한글과 em-dash 가 깨져 뒤따르는 SnakeYAML / Jackson 이
 * 파싱을 거부합니다.
 *
 * <p>charset 을 명시하지 않는 서버(ChartMuseum, 일부 OpenStack) 응답을 파싱할 때 쓰세요.
 */
public final class RestTemplateUtf8 {

    private RestTemplateUtf8() {
        // static utility
    }

    /**
     * GET 응답을 UTF-8 문자열로 돌려줍니다. status 확인은 호출자 책임입니다.
     *
     * @return 디코딩된 body. body 가 null 이면 빈 문자열.
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
