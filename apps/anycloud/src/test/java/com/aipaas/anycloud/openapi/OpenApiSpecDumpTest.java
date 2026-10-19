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
 * OpenAPI 스펙을 파일로 떨어뜨린다. publish-openapi 워크플로가 이 산출물을 발행한다.
 *
 * <p>워크플로가 직접 앱을 띄우던 방식은 쓸 수 없다. {@code bootRun} 은 main 런타임 클래스패스만
 * 보는데 test 프로파일 설정은 {@code src/test/resources} 에 있어 datasource 조차 잡히지 않는다.
 * 여기서 뽑으면 Testcontainers 로 이미 검증된 부팅 경로를 그대로 쓴다.
 *
 * <p>{@code 0-all} 그룹을 친다. {@code springdoc.api-docs.groups.enabled=true} 라 그룹 없는
 * 경로는 스펙이 아니라 그룹 목록을 돌려준다.
 */
@AutoConfigureMockMvc
class OpenApiSpecDumpTest extends AbstractIntegrationTest {

    /** 워크플로가 읽는 경로. 바꾸면 publish-openapi.yml 도 같이 바꿔야 한다. */
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

        // 그룹 목록이 와도 JSON 파싱은 통과한다. 스펙인지는 필수 필드로 가린다.
        assertThat(spec.has("openapi")).as("openapi 버전 필드").isTrue();
        assertThat(spec.path("paths").size()).as("경로 수").isPositive();

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(
                OUTPUT, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec), StandardCharsets.UTF_8);
    }
}
