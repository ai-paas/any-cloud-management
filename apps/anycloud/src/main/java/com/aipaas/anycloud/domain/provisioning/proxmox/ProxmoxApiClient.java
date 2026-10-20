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
 * <p>자격증명별 TLS 신뢰 설정이 달라 공용 {@code RestTemplate} 사용 불가. PVE 는 자체 서명
 * 인증서가 기본이고 IP 접속 시 호스트명도 불일치. 신뢰 해제 범위를 이 호출로 한정. 공용
 * 클라이언트 변경 시 다른 CSP 호출까지 영향.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProxmoxApiClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;

    /** 자격증명의 PVE 접근 가능 여부. 토큰 오류나 만료 시 401 응답. */
    public JsonNode version(Map<String, String> credentialEnv) {
        return get(credentialEnv, "/api2/json/version");
    }

    /**
     * 조회 실패 시 {@code null} 반환. 호출자가 다음 단계로 진행.
     *
     * <p>CSP API 장애로 프로비저닝을 차단하면 복구 수단 부재. 401 은 토큰 문제로 진단이
     * 명확하므로 즉시 통보.
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
     * PVE 토큰 생성 화면이 분리해 표시하는 두 값을 provider 입력 형식의 한 줄로 결합.
     *
     * <p>한쪽만 채워 결합하면 {@code user@realm!name=} 형태 전달. 형식이 유효해 보여 첫 API
     * 호출 시점에 실패.
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
     * <p>PVE 는 자체 서명 인증서가 기본이고 IP 접속 시 SAN 미포함. 신뢰만 해제하면 호스트명
     * 검증에서 {@code No subject alternative names matching IP address} 로 실패.
     *
     * <p>해제 범위를 이 클라이언트로 한정. 공용 RestTemplate 변경 시 다른 CSP 호출까지 영향.
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
