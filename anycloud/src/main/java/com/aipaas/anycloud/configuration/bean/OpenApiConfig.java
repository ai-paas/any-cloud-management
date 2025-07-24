package com.aipaas.anycloud.configuration.bean;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <pre>
 * ClassName : OpenApiConfig
 * Type : class
 * Description : Swagger springdoc-ui 설정과 관련된 함수를 포함하고 있는 클래스입니다.
 * Related : springdoc-openapi-ui
 * </pre>
 */
@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI openAPI() {

		Info info = new Info()
			.title("AnyCloud API Document")
			.version("v0.0.1")
			.description("AnyCloud RestAPI Swagger Docs.");

		return new OpenAPI().info(info);
	}
}
