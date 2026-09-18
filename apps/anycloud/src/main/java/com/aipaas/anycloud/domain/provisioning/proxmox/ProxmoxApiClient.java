package com.aipaas.anycloud.domain.provisioning.proxmox;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.SSLContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/**
 * PVE REST API 읽기 호출.
 *
 * <p>자격증명마다 TLS 신뢰 여부가 달라 공용 {@code RestTemplate} 을 쓰지 못한다. PVE 는 자체 서명
 * 인증서가 기본이고, IP 로 접속하면 이름도 맞지 않는다. 신뢰를 끄는 범위를 이 호출 하나로 묶어
 * 둔다 — 공용 클라이언트를 고치면 다른 CSP 호출까지 열린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProxmoxApiClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;

    /** 자격증명이 PVE 까지 통하는지. 토큰이 틀리거나 만료됐으면 401 이 온다. */
    public JsonNode version(Map<String, String> credentialEnv) {
        return get(credentialEnv, "/api2/json/version");
    }

    /**
     * 조회에 실패하면 {@code null} — 호출자가 다음 단계로 넘긴다.
     *
     * <p>CSP API 장애로 프로비저닝을 막으면 복구 수단이 없다. 다만 401 은 토큰 문제라 진단이
     * 명확하므로 그 자리에서 알린다.
     */
    public JsonNode get(Map<String, String> credentialEnv, String path) {
        String endpoint = trimmed(credentialEnv.get("PROXMOX_VE_ENDPOINT"));
        String token = apiToken(credentialEnv);
        if (endpoint.isEmpty() || token == null) {
            return null;
        }
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        try (CloseableHttpClient client = newClient(insecure(credentialEnv))) {
            HttpGet request = new HttpGet(base + path);
            request.addHeader("Authorization", "PVEAPIToken=" + token);
            return client.execute(request, response -> {
                int status = response.getCode();
                if (status == 401) {
                    throw new CustomException(
                            "Proxmox API 인증에 실패했습니다. 토큰 ID, 시크릿, 만료일을 확인합니다.", ErrorCode.INVALID_INPUT_VALUE);
                }
                if (status >= 300) {
                    log.warn("Proxmox API {} 가 {} 를 반환했다", path, status);
                    return null;
                }
                return objectMapper
                        .readTree(EntityUtils.toString(response.getEntity()))
                        .path("data");
            });
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Proxmox API {} 에 닿지 못했다: {}", path, e.toString());
            return null;
        }
    }

    /**
     * PVE 가 토큰 생성 화면에서 따로 보여주는 두 값을 provider 가 받는 한 줄로 잇는다.
     *
     * <p>한쪽만 채워 이어 붙이면 {@code user@realm!name=} 같은 값이 넘어간다. 형식은 맞아 보여서
     * 첫 API 호출에서야 실패한다.
     */
    private static String apiToken(Map<String, String> env) {
        String id = trimmed(env.get("PROXMOX_VE_API_TOKEN_ID"));
        String secret = trimmed(env.get("PROXMOX_VE_API_TOKEN_SECRET"));
        return id.isEmpty() || secret.isEmpty() ? null : id + "=" + secret;
    }

    private static boolean insecure(Map<String, String> env) {
        return Boolean.parseBoolean(trimmed(env.get("PROXMOX_VE_INSECURE")));
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * {@code insecure} 는 인증서 신뢰와 호스트명 검증을 함께 끈다.
     *
     * <p>PVE 는 자체 서명 인증서가 기본이고, IP 로 접속하면 SAN 에도 없다. 신뢰만 끄면 호스트명
     * 검증에서 막혀 {@code No subject alternative names matching IP address} 로 실패한다.
     *
     * <p>끄는 범위를 이 클라이언트 하나로 묶어 둔다 — 공용 RestTemplate 을 고치면 다른 CSP 호출까지
     * 열린다.
     */
    private CloseableHttpClient newClient(boolean insecure) throws Exception {
        SSLConnectionSocketFactoryBuilder tls = SSLConnectionSocketFactoryBuilder.create();
        if (insecure) {
            SSLContext context = SSLContextBuilder.create()
                    .loadTrustMaterial((chain, authType) -> true)
                    .build();
            tls.setSslContext(context).setHostnameVerifier(NoopHostnameVerifier.INSTANCE);
        }
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setSSLSocketFactory(tls.build())
                        .setDefaultConnectionConfig(org.apache.hc.client5.http.config.ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.of(TIMEOUT))
                                .build())
                        .build())
                .setDefaultRequestConfig(org.apache.hc.client5.http.config.RequestConfig.custom()
                        .setResponseTimeout(Timeout.of(TIMEOUT))
                        .build())
                .build();
    }
}
