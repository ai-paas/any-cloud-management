package com.aipaas.anycloud.configuration.bean;

import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.yaml.snakeyaml.Yaml;

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
}
