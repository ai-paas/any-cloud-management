package com.aipaas.anycloud.common.util;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * 응답을 {@code byte[]} 로 수신해 UTF-8 로 직접 디코딩.
 *
 * <p>{@code String.class} 수신 시 {@code StringHttpMessageConverter} 가 Content-Type 의 charset
 * 을 참조하고, 부재 시 ISO-8859-1 로 해석. 한글과 긴 대시가 손상돼 후속 SnakeYAML, Jackson 이
 * 파싱 거부.
 *
 * <p>charset 미명시 서버(ChartMuseum, 일부 OpenStack) 응답 파싱에 사용.
 */
public final class RestTemplateUtf8 {

    private RestTemplateUtf8() {
        // static utility
    }

    /**
     * GET 응답을 UTF-8 문자열로 반환. status 확인은 호출자 책임.
     *
     * @return 디코딩된 body. body 가 null 이면 빈 문자열
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
