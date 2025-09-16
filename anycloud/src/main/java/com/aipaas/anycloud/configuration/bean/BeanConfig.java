package com.aipaas.anycloud.configuration.bean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.modelmapper.ModelMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.yaml.snakeyaml.Yaml;

import javax.net.ssl.SSLContext;

/**
 * <pre>
 * ClassName : BeanConfig
 * Type : class
 * Description : Bean으로 등록한 외부 패키지와 관련된 함수를 포함하고 있는 클래스입니다.
 * Related : All
 * </pre>
 */
@Configuration
@RequiredArgsConstructor
public class BeanConfig {

	@Bean
	public ModelMapper modelMapper() {
		return new ModelMapper();
	}

	@Bean
	public Yaml yaml() {
		return new Yaml();
	}

	@Bean
	public WebClient webClient() {
		return WebClient.builder()
				.build();
	}

	@Bean
	public RestTemplate restTemplate() {
		try {
			// SSL 컨텍스트 생성 (모든 인증서 신뢰)
			SSLContext sslContext = SSLContextBuilder
					.create()
					.loadTrustMaterial(TrustAllStrategy.INSTANCE)
					.build();

			// HTTP 클라이언트 설정
			CloseableHttpClient httpClient = HttpClients.custom()
					.setConnectionManager(
							PoolingHttpClientConnectionManagerBuilder.create()
									.setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
											.setSslContext(sslContext)
											.build())
									.build())
					.build();

			// RestTemplate 설정
			HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
			factory.setHttpClient(httpClient);
			factory.setConnectTimeout(10000); // 10초
			factory.setConnectionRequestTimeout(10000); // 10초

			RestTemplate restTemplate = new RestTemplate(factory);

			// User-Agent 헤더 추가
			restTemplate.getInterceptors().add((request, body, execution) -> {
				if (!request.getHeaders().containsKey("User-Agent")) {
					request.getHeaders().add("User-Agent", "Helm-Chart-Manager/1.0");
				}
				return execution.execute(request, body);
			});

			return restTemplate;

		} catch (Exception e) {
			// SSL 설정 실패 시 기본 RestTemplate 반환
			return new RestTemplate();
		}
	}

	@Bean
	public ObjectMapper objectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule());
		mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		return mapper;
	}
}
