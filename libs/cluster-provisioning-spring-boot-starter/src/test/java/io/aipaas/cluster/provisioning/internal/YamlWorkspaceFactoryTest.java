package io.aipaas.cluster.provisioning.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.aipaas.cluster.provisioning.program.yaml.PulumiProgram;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class YamlWorkspaceFactoryTest {

    private PulumiProgram program() {
        return PulumiProgram.builder("anycloud-k8s")
                .resource("net", "openstack:networking/network:Network", Map.of("name", "demo"))
                .build();
    }

    @Test
    void writesPulumiYamlIntoFreshDirectory() throws IOException {
        Path workDir = YamlWorkspaceFactory.create(program());
        try {
            Path file = workDir.resolve("Pulumi.yaml");

            assertThat(Files.exists(file)).isTrue();
            assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("runtime: yaml");
        } finally {
            YamlWorkspaceFactory.delete(workDir);
        }
    }

    @Test
    void eachCallGetsItsOwnDirectory() {
        // 동시에 여러 스택이 돌 수 있다. 같은 디렉토리를 쓰면 서로 덮어쓴다.
        Path a = YamlWorkspaceFactory.create(program());
        Path b = YamlWorkspaceFactory.create(program());
        try {
            assertThat(a).isNotEqualTo(b);
        } finally {
            YamlWorkspaceFactory.delete(a);
            YamlWorkspaceFactory.delete(b);
        }
    }

    @Test
    void deleteRemovesDirectoryAndContents() {
        Path workDir = YamlWorkspaceFactory.create(program());

        YamlWorkspaceFactory.delete(workDir);

        assertThat(Files.exists(workDir)).isFalse();
    }

    @Test
    void deleteIsIdempotent() {
        // 예외 경로에서 두 번 불릴 수 있다. 정리 실패가 원래 예외를 덮으면 안 된다.
        Path workDir = YamlWorkspaceFactory.create(program());
        YamlWorkspaceFactory.delete(workDir);

        assertThatCode(() -> YamlWorkspaceFactory.delete(workDir)).doesNotThrowAnyException();
    }

    @Test
    void deleteToleratesNull() {
        assertThatCode(() -> YamlWorkspaceFactory.delete(null)).doesNotThrowAnyException();
    }

    @Test
    void programContentIsWrittenVerbatim() throws IOException {
        // user-data 처럼 특수문자가 섞인 값이 파일을 거치며 변형되면 안 된다.
        PulumiProgram program = PulumiProgram.builder("p")
                .resource("vm", "t", Map.of("userData", "#!/bin/bash\necho \"hi\"\n"))
                .build();
        Path workDir = YamlWorkspaceFactory.create(program);
        try {
            assertThat(Files.readString(workDir.resolve("Pulumi.yaml"), StandardCharsets.UTF_8))
                    .isEqualTo(program.toYaml());
        } finally {
            YamlWorkspaceFactory.delete(workDir);
        }
    }
}
