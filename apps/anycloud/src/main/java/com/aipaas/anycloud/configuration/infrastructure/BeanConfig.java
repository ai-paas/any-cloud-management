package com.aipaas.anycloud.configuration.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import javax.net.ssl.SSLContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 외부 라이브러리 Bean 등록 — 보안 기본값 집중 (YAML SafeConstructor, TLS truststore 등). */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class BeanConfig {

    @Bean
    public ModelMapper modelMapper() {
        return new ModelMapper();
    }

    /** SnakeYAML: SafeConstructor (RCE 차단) + alias 50개·중복키 거부·3MB cap (DoS 방어). */
    @Bean
    public Yaml yaml() {
        LoaderOptions opts = new LoaderOptions();
        opts.setMaxAliasesForCollections(50);
        opts.setAllowDuplicateKeys(false);
        opts.setCodePointLimit(3 * 1024 * 1024);
        return new Yaml(new SafeConstructor(opts));
    }

    @Bean
    public WebClient webClient() {
        return WebClient.builder().build();
    }

    /** RestTemplate: Helm/외부 HTTPS 호출. truststore = JVM cacerts (insecure-tls=true 면 dev fallback). */
    @Bean
    public RestTemplate restTemplate(
            @Value("${anycloud.http.insecure-tls:false}") boolean insecureTls,
            @Value("${anycloud.http.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${anycloud.http.request-timeout-ms:10000}") int requestTimeoutMs,
            ObjectProvider<BuildProperties> buildPropertiesProvider) {
        try {
            SSLContextBuilder sslBuilder = SSLContextBuilder.create();
            if (insecureTls) {
                log.warn("*** anycloud.http.insecure-tls=true *** ALL TLS certificates accepted. "
                        + "DO NOT use in production — MITM risk on Helm repo / external HTTPS.");
                sslBuilder.loadTrustMaterial((chain, authType) -> true);
            }
            // insecureTls=false 면 SSLContextBuilder 기본 = JVM cacerts 사용 (안전한 default).
            SSLContext sslContext = sslBuilder.build();

            CloseableHttpClient httpClient = HttpClients.custom()
                    .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                            .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                                    .setSslContext(sslContext)
                                    .build())
                            .build())
                    .build();

            HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
            factory.setHttpClient(httpClient);
            factory.setConnectTimeout(connectTimeoutMs);
            factory.setConnectionRequestTimeout(requestTimeoutMs);

            RestTemplate restTemplate = new RestTemplate(factory);

            // User-Agent = "anycloud-backend/<version>" — build info 기반, 없으면 dev fallback.
            String userAgent = buildUserAgent(buildPropertiesProvider.getIfAvailable());
            restTemplate.getInterceptors().add((request, body, execution) -> {
                if (!request.getHeaders().containsKey("User-Agent")) {
                    request.getHeaders().add("User-Agent", userAgent);
                }
                return execution.execute(request, body);
            });

            return restTemplate;

        } catch (Exception e) {
            log.error("RestTemplate SSL setup failed — falling back to default", e);
            return new RestTemplate();
        }
    }

    /**
     * CSP API 전용 RestTemplate. 6 provider (GCP/Azure/OCI/Alibaba/OpenStack/DigitalOcean)
     * 의 listSpecs / listImages 호출이 사용. fail-fast 짧은 timeout — 가짜 cred 또는 일시 장애 시
     * controller 전체 latency 가 누적되지 않게.
     *
     * <p>vs default {@code restTemplate}: 후자는 Helm chart download / 외부 HTTPS 등 큰 payload 도
     * 처리 (10s/10s). CSP API 는 metadata 만이라 짧게 잡아도 충분.
     */
    @Bean("cspRestTemplate")
    public RestTemplate cspRestTemplate(
            @Value("${anycloud.csp.http.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${anycloud.csp.http.read-timeout-ms:5000}") int readTimeoutMs) {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setConnectionRequestTimeout(readTimeoutMs);
        // HttpClient 5 의 response timeout = socket read timeout. AWS Ec2 와 동일 정책.
        factory.setHttpClient(HttpClients.custom()
                .setDefaultRequestConfig(org.apache.hc.client5.http.config.RequestConfig.custom()
                        .setResponseTimeout(readTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .build())
                .build());
        return new RestTemplate(factory);
    }

    private static String buildUserAgent(BuildProperties bp) {
        if (bp == null) {
            return "anycloud-backend/dev";
        }
        String name = bp.getName() == null ? "anycloud-backend" : bp.getName();
        String ver = bp.getVersion() == null ? "0.0.0" : bp.getVersion();
        return name + "/" + ver;
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
