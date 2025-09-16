package com.aipaas.anycloud.configuration.bean;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

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
public class WebConfig implements WebMvcConfigurer {
  @Override
  public void addCorsMappings(CorsRegistry registry) {
      registry.addMapping("/**")
          .allowedOriginPatterns("*")  // 개발환경에서 모든 origin 허용
          .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH")
          .allowedHeaders("*")
          .allowCredentials(true)
          .maxAge(3600);
  }
}