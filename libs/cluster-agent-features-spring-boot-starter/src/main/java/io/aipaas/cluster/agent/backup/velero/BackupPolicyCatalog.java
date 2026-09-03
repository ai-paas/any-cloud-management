package io.aipaas.cluster.agent.backup.velero;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StreamUtils;

/**
 * Classpath 의 {@code velero-policies/*.yaml} 을 로드해 id 별로 보관.
 *
 * <p>observability-starter 의 {@code AlertRuleCatalog} 와 동일 패턴. startup 시 한 번 로드, immutable.
 * 호스트가 정책 추가하려면 본 catalog 를 override 하거나 자체 빈으로 합성.
 *
 * <p>displayName / schedule / ttl 은 manifestYaml 자체에서 grep 한다 — 별도 metadata 파일 불요.
 */
@Slf4j
public class BackupPolicyCatalog {

	private static final String CLASSPATH_PATTERN = "classpath*:velero-policies/*.yaml";

	private final Map<String, BackupPolicy> byId;

	public BackupPolicyCatalog() {
		this(CLASSPATH_PATTERN);
	}

	BackupPolicyCatalog(String classpathPattern) {
		this.byId = load(classpathPattern);
		log.info("BackupPolicyCatalog loaded {} policy(ies): {}", byId.size(), byId.keySet());
	}

	public List<BackupPolicy> list() {
		return List.copyOf(byId.values());
	}

	public Optional<BackupPolicy> byId(String id) {
		return Optional.ofNullable(byId.get(id));
	}

	public List<String> ids() {
		return List.copyOf(byId.keySet());
	}

	// ----- internal -----

	private static Map<String, BackupPolicy> load(String pattern) {
		PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
		Resource[] resources;
		try {
			resources = resolver.getResources(pattern);
		} catch (IOException e) {
			throw new UncheckedIOException("failed to scan " + pattern, e);
		}
		java.util.Arrays.sort(resources, java.util.Comparator.comparing(Resource::getFilename));

		Map<String, BackupPolicy> out = new LinkedHashMap<>();
		for (Resource r : resources) {
			String filename = r.getFilename();
			if (filename == null || !filename.endsWith(".yaml")) {
				continue;
			}
			String id = filename.substring(0, filename.length() - ".yaml".length());
			String yaml;
			try {
				yaml = StreamUtils.copyToString(r.getInputStream(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				log.warn("velero-policy load skipped: {} — {}", filename, e.getMessage());
				continue;
			}
			out.put(id, new BackupPolicy(
					id,
					toDisplayName(id),
					describe(id),
					extractField(yaml, "schedule:"),
					extractField(yaml, "ttl:"),
					yaml));
		}
		return Collections.unmodifiableMap(out);
	}

	/** 단순 line-grep — full YAML parser 회피 (분기 + 의존성 최소). */
	private static String extractField(String yaml, String prefix) {
		for (String line : yaml.split("\n")) {
			String t = line.trim();
			if (t.startsWith(prefix)) {
				return t.substring(prefix.length())
						.trim()
						.replace("\"", "")
						.replace("'", "");
			}
		}
		return "";
	}

	private static String toDisplayName(String id) {
		return switch (id) {
			case "daily-full-cluster" -> "매일 전체 cluster 백업 (02:00 UTC, 30일 보존)";
			case "hourly-workloads" -> "시간 단위 워크로드 백업 (시스템 ns 제외, 7일 보존)";
			case "weekly-pv-snapshots" -> "주간 PV snapshot (일 03:00 UTC, 90일 보존)";
			default -> id;
		};
	}

	private static String describe(String id) {
		return switch (id) {
			case "daily-full-cluster" ->
					"K8s 자원 + PV snapshot 전체. cluster 통째 재해 복구의 baseline.";
			case "hourly-workloads" ->
					"user namespace workload 만 — kube-system / velero / monitoring 제외. PV 포함.";
			case "weekly-pv-snapshots" ->
					"PV snapshot 위주 — storage 비용 큼. DR 전용 long-retention.";
			default -> "anycloud-default Velero schedule.";
		};
	}
}
