package io.aipaas.cluster.provisioning.service;

import io.aipaas.cluster.provisioning.core.ClusterDescriptor;
import io.aipaas.cluster.provisioning.core.ClusterDescriptorRepository;
import io.aipaas.cluster.provisioning.core.PulumiBackupPropertiesProvider;
import io.aipaas.cluster.provisioning.core.PulumiCommandResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Pulumi state 백업의 "실제로 복구 가능한가" 정기 검증 (Day-2 §6 후속).
 *
 * <p>백업이 떨어진다고 복구가 보장되는 것은 아니다(잘린 파일 / 권한 / schema 변경 등). 본 scheduler 는
 * (1) shape validation — 최신 backup JSON 의 {@code version} + {@code deployment.resources} 존재 및
 * resource 수 임계, (2) deep validation(opt-in) — 임시 stack 에 import 후 즉시 제거 라운드트립 — 을 수행.
 *
 * <p>정책은 {@link PulumiBackupPropertiesProvider}, cluster 목록은 {@link ClusterDescriptorRepository}
 * 포트로 host 가 공급. {@code isRestoreDryRunEnabled()} 가 false 면 cron 이 떠도 즉시 no-op.
 */
@Slf4j
@RequiredArgsConstructor
public class PulumiStateBackupValidator {

	private static final Duration IMPORT_TIMEOUT = Duration.ofMinutes(2);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final PulumiBackupPropertiesProvider properties;
	private final PulumiCommandService pulumiCommandService;
	private final ClusterDescriptorRepository clusterDescriptorRepository;
	private final MeterRegistry meterRegistry;

	@Scheduled(cron = "${cluster-provisioning.state-backup.restore-dry-run.cron:0 30 3 * * *}")
	@SchedulerLock(name = "pulumiStateBackupValidate", lockAtMostFor = "PT1H", lockAtLeastFor = "PT10M")
	public void validate() {
		if (!properties.isRestoreDryRunEnabled()) {
			return;
		}
		Path baseDir = Paths.get(properties.getDirectory());
		if (!Files.isDirectory(baseDir)) {
			log.warn("Restore dry-run skip: backup dir not found ({}).", baseDir);
			return;
		}

		List<ClusterDescriptor> clusters = clusterDescriptorRepository.findAll();
		int success = 0;
		int failure = 0;
		int missing = 0;

		for (ClusterDescriptor cluster : clusters) {
			String stackName = cluster.getStackName();
			if (stackName == null || stackName.isBlank()) {
				continue;
			}
			Optional<Path> latest = findLatestBackup(baseDir, stackName);
			if (latest.isEmpty()) {
				log.warn("Restore dry-run: no backup file found for stack {}", stackName);
				missing++;
				continue;
			}
			ValidationResult result = validateShape(latest.get());
			if (!result.ok) {
				log.warn("Restore dry-run FAIL (shape): stack={}, file={}, reason={}",
						stackName, latest.get(), result.reason);
				failure++;
				recordPerStack(stackName, "failure-shape");
				continue;
			}
			if (properties.isDeepValidation()) {
				boolean deep = runDeepValidation(stackName, latest.get());
				if (!deep) {
					failure++;
					recordPerStack(stackName, "failure-deep");
					continue;
				}
			}
			log.info("Restore dry-run OK: stack={}, file={}, resources={}",
					stackName, latest.get().getFileName(), result.resourceCount);
			success++;
			recordPerStack(stackName, "success");
		}

		recordAggregate("success", success);
		recordAggregate("failure", failure);
		recordAggregate("missing", missing);
		log.info("Pulumi backup restore dry-run complete: success={}, failure={}, missing={}",
				success, failure, missing);
	}

	private Optional<Path> findLatestBackup(Path baseDir, String stackName) {
		List<Path> candidates = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir, stackName + "-*.json")) {
			stream.forEach(candidates::add);
		} catch (IOException e) {
			log.warn("Restore dry-run: cannot list backup dir for {} — {}", stackName, e.toString());
			return Optional.empty();
		}
		return candidates.stream()
				.max(Comparator.comparing(p -> {
					try {
						return Files.getLastModifiedTime(p).toInstant();
					} catch (IOException e) {
						return java.time.Instant.EPOCH;
					}
				}));
	}

	/**
	 * Pulumi {@code stack export} 의 JSON shape 검증. Jackson 으로 lazy parse 하여 큰 백업 파일도
	 * 메모리 효율적으로 검사한다.
	 */
	private ValidationResult validateShape(Path file) {
		try {
			JsonNode root = MAPPER.readTree(file.toFile());
			if (root == null || root.isMissingNode()) {
				return ValidationResult.fail("empty or unparsable JSON");
			}
			JsonNode version = root.get("version");
			if (version == null || !version.isInt()) {
				return ValidationResult.fail("missing or non-int 'version'");
			}
			JsonNode deployment = root.get("deployment");
			if (deployment == null || !deployment.isObject()) {
				return ValidationResult.fail("missing 'deployment' object");
			}
			JsonNode resources = deployment.get("resources");
			if (resources == null || !resources.isArray()) {
				return ValidationResult.fail("missing 'deployment.resources' array");
			}
			int count = resources.size();
			int min = Math.max(0, properties.getMinResourceCount());
			if (count < min) {
				return ValidationResult.fail("resource count " + count + " < min " + min);
			}
			return ValidationResult.ok(count);
		} catch (IOException e) {
			return ValidationResult.fail("io: " + e.getMessage());
		} catch (RuntimeException e) {
			return ValidationResult.fail("parse: " + e.getMessage());
		}
	}

	/**
	 * Pulumi CLI 로 임시 stack 에 import 후 즉시 제거. 라운드트립이 성공하면 backup 이 실제 복구
	 * 가능하다는 강한 증거. 실패 케이스(권한, 손상 등)도 조기 감지.
	 */
	private boolean runDeepValidation(String stackName, Path backupFile) {
		String tempStack = stackName + "-restore-test";
		try {
			PulumiCommandResult init = pulumiCommandService.run(
					List.of("stack", "init", tempStack, "--non-interactive"),
					IMPORT_TIMEOUT);
			if (!init.isSuccess() && !init.getStderr().contains("already exists")) {
				log.warn("Restore dry-run deep init failed: stack={}, stderr={}", tempStack, init.getStderr());
				return false;
			}
			PulumiCommandResult select = pulumiCommandService.selectStack(tempStack);
			if (!select.isSuccess()) {
				log.warn("Restore dry-run deep select failed: stack={}, stderr={}", tempStack, select.getStderr());
				return false;
			}
			PulumiCommandResult importResult = pulumiCommandService.run(
					List.of("stack", "import", "--file", backupFile.toAbsolutePath().toString()),
					IMPORT_TIMEOUT);
			boolean success = importResult.isSuccess();
			if (!success) {
				log.warn("Restore dry-run deep import failed: stack={}, stderr={}",
						tempStack, importResult.getStderr());
			}
			return success;
		} catch (RuntimeException e) {
			log.warn("Restore dry-run deep exception: stack={}, error={}", tempStack, e.toString());
			return false;
		} finally {
			// 임시 stack 흔적 제거. 실패해도 다음 cycle 에서 'already exists' 처리됨.
			try {
				pulumiCommandService.run(
						List.of("stack", "rm", tempStack, "--yes", "--non-interactive"),
						IMPORT_TIMEOUT);
			} catch (RuntimeException ignore) {
				// noop
			}
		}
	}

	private void recordAggregate(String result, int count) {
		if (count <= 0) {
			return;
		}
		Counter.builder("cluster_provisioning.pulumi.backup.restore.dryrun")
				.tags(Tags.of("result", result))
				.register(meterRegistry)
				.increment(count);
	}

	private void recordPerStack(String stack, String result) {
		Counter.builder("cluster_provisioning.pulumi.backup.restore.dryrun.stack")
				.tags(Tags.of("stack", stack, "result", result))
				.register(meterRegistry)
				.increment();
	}

	private record ValidationResult(boolean ok, int resourceCount, String reason) {
		static ValidationResult ok(int count) {
			return new ValidationResult(true, count, null);
		}

		static ValidationResult fail(String reason) {
			return new ValidationResult(false, 0, reason);
		}
	}
}
