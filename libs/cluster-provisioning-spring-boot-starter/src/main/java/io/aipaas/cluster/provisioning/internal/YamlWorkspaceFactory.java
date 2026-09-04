package io.aipaas.cluster.provisioning.internal;

import io.aipaas.cluster.provisioning.program.yaml.PulumiProgram;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * YAML 프로그램을 담을 임시 workDir 관리.
 *
 * <p>스택 상태는 workDir 이 아니라 백엔드(S3/RustFS)에 있다. workDir 은 프로그램 정의만 담으므로
 * 작업이 끝나면 지워도 된다.
 */
@Slf4j
public final class YamlWorkspaceFactory {

    private static final String PROGRAM_FILE = "Pulumi.yaml";

    private YamlWorkspaceFactory() {}

    /** 호출마다 새 디렉토리 — 동시에 여러 스택이 돌 때 서로 덮어쓰지 않도록. */
    public static Path create(PulumiProgram program) {
        try {
            Path workDir = Files.createTempDirectory("anycloud-pulumi-");
            Files.writeString(workDir.resolve(PROGRAM_FILE), program.toYaml(), StandardCharsets.UTF_8);
            return workDir;
        } catch (IOException e) {
            throw new UncheckedIOException("Pulumi 프로그램 workDir 생성 실패", e);
        }
    }

    /** 예외 경로에서 두 번 불릴 수 있다. 정리 실패가 원래 예외를 덮으면 안 되므로 삼킨다. */
    public static void delete(Path workDir) {
        if (workDir == null || !Files.exists(workDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(workDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("workDir 정리 중 파일 삭제 실패 {}: {}", path, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("workDir 정리 실패 {}: {}", workDir, e.toString());
        }
    }
}
