package com.aipaas.anycloud.configuration.infrastructure;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger / springdoc-openapi 설정.
 * <p>
 * v1 자원별 group 분리 — Swagger UI 우상단 드롭다운으로 탐색.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        Info info = new Info()
                .title("AnyCloud API Document")
                .version("v1.0.0")
                .description("AnyCloud RESTful API. /v1 로 시작하는 일관된 자원 모델. " + "Swagger UI 우상단 group 드롭다운으로 도메인 선택.");
        return new OpenAPI().info(info);
    }

    @Bean
    public GroupedOpenApi clustersGroup() {
        return GroupedOpenApi.builder()
                .group("1-clusters")
                .displayName("Clusters")
                .pathsToMatch("/v1/clusters/**", "/v1/cluster-validations/**")
                .build();
    }

    @Bean
    public GroupedOpenApi operationsGroup() {
        return GroupedOpenApi.builder()
                .group("2-operations")
                .displayName("Operations (LRO)")
                .pathsToMatch("/v1/operations/**")
                .build();
    }

    @Bean
    public GroupedOpenApi providersAndCredentialsGroup() {
        return GroupedOpenApi.builder()
                .group("3-providers-credentials")
                .displayName("Providers & Credentials")
                .pathsToMatch("/v1/providers/**", "/v1/credentials/**")
                .build();
    }

    @Bean
    public GroupedOpenApi helmGroup() {
        return GroupedOpenApi.builder()
                .group("4-helm")
                .displayName("Helm Repos & Charts")
                .pathsToMatch("/v1/helm-repos/**")
                .build();
    }

    @Bean
    public GroupedOpenApi workflowGroup() {
        return GroupedOpenApi.builder()
                .group("5-workflow")
                .displayName("Workflow (admin)")
                .pathsToMatch("/v1/workflow/**")
                .build();
    }

    @Bean
    public GroupedOpenApi auditGroup() {
        return GroupedOpenApi.builder()
                .group("6-audit")
                .displayName("Audit Log")
                .pathsToMatch("/v1/audit-logs/**")
                .build();
    }

    /** 전 endpoint (회귀 / 디버깅용). */
    @Bean
    public GroupedOpenApi allGroup() {
        return GroupedOpenApi.builder()
                .group("0-all")
                .displayName("All endpoints")
                .pathsToMatch("/**")
                .build();
    }
}
