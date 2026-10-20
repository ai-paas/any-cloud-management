package com.aipaas.anycloud.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipaas.anycloud.testsupport.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OpenAPI 스펙을 파일로 출력. publish-openapi 워크플로가 이 산출물을 발행.
 *
 * <p>워크플로가 앱을 직접 기동하는 방식은 사용 불가. {@code bootRun} 은 main 런타임 클래스패스만
 * 참조하는데 test 프로파일 설정이 {@code src/test/resources} 에 있어 datasource 조차 미인식.
 * 이 경로는 Testcontainers 로 검증된 부팅 경로를 그대로 사용.
 *
 * <p>{@code 0-all} 그룹 호출. {@code springdoc.api-docs.groups.enabled=true} 이므로 그룹 없는
 * 경로는 스펙이 아닌 그룹 목록 반환.
 */
@AutoConfigureMockMvc
class OpenApiSpecDumpTest extends AbstractIntegrationTest {

    /** 워크플로가 읽는 경로. 변경 시 publish-openapi.yml 도 함께 수정 필요. */
    private static final Path OUTPUT = Path.of("build", "openapi", "openapi.json");

    private static final String ALL_GROUP = "/docs/json/0-all";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void dumpsFullSpec() throws Exception {
        String body = mockMvc.perform(get(ALL_GROUP))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode spec = mapper.readTree(body);

        // 그룹 목록 수신 시에도 JSON 파싱은 통과. 스펙 여부는 필수 필드로 판정.
        assertThat(spec.has("openapi")).as("openapi 버전 필드").isTrue();
        assertThat(spec.path("paths").size()).as("경로 수").isPositive();

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(
                OUTPUT, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec), StandardCharsets.UTF_8);
    }
}
