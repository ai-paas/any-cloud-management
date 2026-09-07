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

/** YAML 프로그램을 담을 임시 workDir 관리. */
@Slf4j
public final class YamlWorkspaceFactory {

    private static final String PROGRAM_FILE = "Pulumi.yaml";

    /**
     * 사전 컴파일된 플러그인이 없는 provider(IBM 등)의 스키마 위치. 프로그램 옆에 {@code sdks/} 로
     * 있어야 타입이 해석된다. 이미지 빌드 때 만들어 두고 여기서 복사한다.
     */
    private static final String SDK_DIR = "sdks";

    private static final String SDK_SOURCE_ENV = "PULUMI_PACKAGE_SDKS";
    private static final String SDK_SOURCE_DEFAULT = "/opt/pulumi-packages/sdks";

    private YamlWorkspaceFactory() {}

    /** 호출마다 새 디렉토리 — 동시에 여러 스택이 돌 때 서로 덮어쓰지 않도록. */
    public static Path create(PulumiProgram program) {
        try {
            Path workDir = Files.createTempDirectory("anycloud-pulumi-");
            Files.writeString(workDir.resolve(PROGRAM_FILE), program.toYaml(), StandardCharsets.UTF_8);
            copySdks(workDir);
            return workDir;
        } catch (IOException e) {
            throw new UncheckedIOException("Pulumi 프로그램 workDir 생성 실패", e);
        }
    }

    /** 스키마가 없으면 해당 provider 만 실패한다. 나머지 provider 의 프로비저닝은 막지 않는다. */
    private static void copySdks(Path workDir) {
        String configured = System.getenv(SDK_SOURCE_ENV);
        Path source = Path.of(configured == null || configured.isBlank() ? SDK_SOURCE_DEFAULT : configured);
        if (!Files.isDirectory(source)) {
            return;
        }
        Path target = workDir.resolve(SDK_DIR);
        try (Stream<Path> paths = Files.walk(source)) {
            paths.forEach(path -> {
                try {
                    Path dest = target.resolve(source.relativize(path).toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(path, dest);
                    }
                } catch (IOException e) {
                    log.warn("package 스키마 복사 실패: {}", e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("package 스키마 디렉토리 탐색 실패: {}", e.toString());
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
