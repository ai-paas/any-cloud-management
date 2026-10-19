package com.aipaas.anycloud.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * backend 에 fabric8 가 다시 들어오는 것을 막는 architecture guard.
 *
 * <p>backend 는 cluster K8s API 를 직접 치지 않습니다. 모든 ops 가 cluster-agent gRPC 를
 * 지나갑니다. 자격증명을 보관하지 않으므로 fabric8 client 를 만들 수단도 없습니다.
 *
 * <p>예외가 필요하면 의존성을 되살리기 전에 먼저 자격증명을 어디에 두는지부터 정해야 합니다.
 */
class Fabric8ImportGuardTest {

    private static final Path BACKEND_MAIN_SRC = Paths.get("src/main/java");
    private static final Pattern FABRIC8_IMPORT =
            Pattern.compile("^import\\s+io\\.fabric8\\.[\\w.]+;\\s*$", Pattern.MULTILINE);

    @Test
    void no_file_imports_fabric8() throws IOException {
        // gradle 은 module rootDir 에서 test 실행 — apps/anycloud/src/main/java 가 정확한 경로.
        assertThat(BACKEND_MAIN_SRC).as("backend main src 디렉토리").exists();

        TreeMap<String, TreeSet<String>> offenders = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(BACKEND_MAIN_SRC)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> scanFile(p, offenders));
        }

        assertThat(offenders)
                .as("backend 는 fabric8 을 쓰지 않습니다. K8s 접근은 cluster-agent gRPC 로만. 위반: %s", offenders)
                .isEmpty();
    }

    private static void scanFile(Path path, TreeMap<String, TreeSet<String>> sink) {
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            String pkg = null;
            TreeSet<String> hits = new TreeSet<>();
            for (String line : lines) {
                String trimmed = line.trim();
                if (pkg == null && trimmed.startsWith("package ")) {
                    pkg = trimmed.substring("package ".length(), trimmed.length() - 1);
                }
                if (FABRIC8_IMPORT.matcher(trimmed).matches()) {
                    hits.add(trimmed);
                }
            }
            if (!hits.isEmpty() && pkg != null) {
                String simple = path.getFileName().toString().replace(".java", "");
                sink.put(pkg + "." + simple, hits);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan " + path, e);
        }
    }
}
