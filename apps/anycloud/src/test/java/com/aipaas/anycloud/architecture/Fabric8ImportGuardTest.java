package com.aipaas.anycloud.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 의 day-2 fabric8 제거 작업이 regression 없이 유지되도록 하는 architecture guard.
 *
 * <p>backend 안에서 {@code import io.fabric8} 가 등장하는 source file 을 한 줄짜리 grep 으로
 * 모아 {@link #ALLOWED_FABRIC8_IMPORTERS} 와 비교. 새 파일에서 fabric8 import 가 추가되면 즉시
 * 실패 → reviewer 가 bootstrap-only allow 인지 결정.
 *
 * <p>의도된 fabric8 사용처 (모두 bootstrap path):
 * <ul>
 *   <li>{@code configuration/KubernetesClientFactory} — kubeconfig → fabric8 client 빌더</li>
 *   <li>{@code service/cluster/AgentBootstrapKubeClient} — bootstrap-only fabric8 client cache</li>
 *   <li>{@code service/cluster/support/KubeconfigParser} — kubeconfig YAML → fabric8 model</li>
 * </ul>
 *
 * <p>새 fabric8 import 가 필요한 합당한 이유가 있으면 {@link #ALLOWED_FABRIC8_IMPORTERS} 에 추가
 * (코드 리뷰에서 day-2 인지 bootstrap 인지 명시).
 */
class Fabric8ImportGuardTest {

    /**
     * Allow-list — 의도적으로 fabric8 을 사용하는 클래스 경로 (package-qualified, simple class name
     * w/o .java). 새 항목 추가 시 PR 에서 "왜 bootstrap-only 인가" 명시 필수.
     */
    private static final Set<String> ALLOWED_FABRIC8_IMPORTERS = Set.of(
            "com.aipaas.anycloud.configuration.persistence.KubernetesClientFactory",
            "com.aipaas.anycloud.domain.cluster.AgentBootstrapKubeClient",
            "com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigParser",
            // First-install chicken-and-egg fallback — agent gRPC 미가용 시 ClusterEntity 의
            // kubeconfig 로 직접 manifest apply. day-2 ops 는 여전히 agent-only (applyResource()).
            "com.aipaas.anycloud.domain.kube.internal.KubeServiceImpl");

    private static final Path BACKEND_MAIN_SRC = Paths.get("src/main/java");
    private static final Pattern FABRIC8_IMPORT =
            Pattern.compile("^import\\s+io\\.fabric8\\.[\\w.]+;\\s*$", Pattern.MULTILINE);

    @Test
    void only_allow_listed_files_import_fabric8() throws IOException {
        // gradle 은 module rootDir 에서 test 실행 — apps/anycloud/src/main/java 가 정확한 경로.
        assertThat(BACKEND_MAIN_SRC).as("backend main src 디렉토리").exists();

        TreeMap<String, TreeSet<String>> offenders = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(BACKEND_MAIN_SRC)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> scanFile(p, offenders));
        }

        // allow-listed 에 포함되지 않은 importer 만 추출.
        TreeMap<String, TreeSet<String>> illegal = new TreeMap<>();
        offenders.forEach((cls, imports) -> {
            if (!ALLOWED_FABRIC8_IMPORTERS.contains(cls)) {
                illegal.put(cls, imports);
            }
        });

        assertThat(illegal)
                .as("Phase 3 이후 fabric8 import 는 bootstrap-only allow-list 의 3 파일만 허용. 새 위반: %s", illegal)
                .isEmpty();

        // 또한 allow-list 에 등록된 파일은 실제로 fabric8 을 import 해야 한다 (오래된 항목 자동 detect).
        Set<String> staleAllowEntries = new TreeSet<>(ALLOWED_FABRIC8_IMPORTERS);
        staleAllowEntries.removeAll(offenders.keySet());
        assertThat(staleAllowEntries)
                .as("Allow-list 의 entry 가 더 이상 fabric8 을 import 하지 않음 — allow-list 를 정리하세요: %s", staleAllowEntries)
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
