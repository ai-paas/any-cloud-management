package io.aipaas.cluster.provisioning.service;

import io.aipaas.cluster.provisioning.core.ClusterDescriptor;
import io.aipaas.cluster.provisioning.core.ClusterDescriptorRepository;
import io.aipaas.cluster.provisioning.core.PulumiBackupPropertiesProvider;
import io.aipaas.cluster.provisioning.core.PulumiCommandResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Pulumi state 정기 백업 (Day-2 §6).
 *
 * <p>cron 주기로 cluster 의 stack 을 {@code pulumi stack export} 으로 dump 하고, retention 초과분은 삭제.
 * 외부 컴포넌트 의존 없이 Pulumi CLI + {@link PulumiCommandService} 만 사용.
 *
 * <p>backup 정책(enabled/directory/retention)은 {@link PulumiBackupPropertiesProvider} 포트로, cluster
 * 목록은 {@link ClusterDescriptorRepository} 포트로 host 가 공급한다. {@code isEnabled()} 가 false 면
 * cron 이 떠도 즉시 no-op. cron schedule 은 starter property {@code cluster-provisioning.state-backup.cron}
 * (기본 매일 03:00) 으로 조정 (host 미설정 시 기본값).
 */
@Slf4j
@RequiredArgsConstructor
public class PulumiStateBackupScheduler {

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

	private final PulumiBackupPropertiesProvider properties;
	private final PulumiCommandService pulumiCommandService;
	private final ClusterDescriptorRepository clusterDescriptorRepository;
	private final MeterRegistry meterRegistry;

	@PostConstruct
	void init() {
		if (!properties.isEnabled()) {
			log.info("Pulumi state backup DISABLED (host provider isEnabled=false).");
			return;
		}
		log.info("Pulumi state backup ENABLED. dir={}, retentionDays={}",
				properties.getDirectory(), properties.getRetentionDays());
		try {
			Files.createDirectories(Paths.get(properties.getDirectory()));
		} catch (IOException e) {
			log.warn("Failed to pre-create backup directory {}: {}", properties.getDirectory(), e.toString());
		}
	}

	@Scheduled(cron = "${cluster-provisioning.state-backup.cron:0 0 3 * * *}")
	@SchedulerLock(name = "pulumiStateBackup", lockAtMostFor = "PT2H", lockAtLeastFor = "PT10M")
	public void backup() {
		if (!properties.isEnabled()) {
			return;
		}
		Path baseDir = Paths.get(properties.getDirectory());
		try {
			Files.createDirectories(baseDir);
		} catch (IOException e) {
			log.error("Pulumi backup directory unavailable: {}", e.toString());
			return;
		}

		List<ClusterDescriptor> clusters = clusterDescriptorRepository.findAll();
		String timestamp = LocalDateTime.now().format(TIMESTAMP);

		int success = 0;
		int failure = 0;
		for (ClusterDescriptor cluster : clusters) {
			String stackName = cluster.getStackName();
			if (stackName == null || stackName.isBlank()) {
				continue;
			}
			Path target = baseDir.resolve(stackName + "-" + timestamp + ".json");
			try {
				PulumiCommandResult selectResult = pulumiCommandService.selectStack(stackName);
				if (!selectResult.isSuccess()) {
					log.warn("Skipping backup for {}: stack select failed — {}", stackName, selectResult.getStderr());
					failure++;
					continue;
				}
				PulumiCommandResult exportResult = pulumiCommandService.run(
						List.of("stack", "export", "--file", target.toAbsolutePath().toString()),
						Duration.ofMinutes(5));
				if (exportResult.isSuccess()) {
					log.info("Pulumi state backup ok: stack={}, file={}", stackName, target);
					success++;
				} else {
					log.warn("Pulumi state backup failed: stack={}, stderr={}", stackName, exportResult.getStderr());
					failure++;
				}
			} catch (RuntimeException e) {
				log.warn("Pulumi state backup exception: stack={}, error={}", stackName, e.toString());
				failure++;
			}
		}

		recordMetric("success", success);
		recordMetric("failure", failure);
		log.info("Pulumi state backup cycle complete: success={}, failure={}", success, failure);

		pruneOldBackups(baseDir);
	}

	private void pruneOldBackups(Path baseDir) {
		Instant cutoff = Instant.now().minus(Duration.ofDays(properties.getRetentionDays()));
		int removed = 0;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir, "*.json")) {
			for (Path file : stream) {
				try {
					if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
						Files.deleteIfExists(file);
						removed++;
					}
				} catch (IOException ignore) {
					// individual file failure 무시 — 다음 cycle에 다시 시도
				}
			}
		} catch (IOException e) {
			log.warn("Failed to list backup directory for prune: {}", e.toString());
		}
		if (removed > 0) {
			log.info("Pulumi state backup retention prune: removed {} file(s)", removed);
			recordMetric("pruned", removed);
		}
	}

	private void recordMetric(String result, int count) {
		if (count <= 0) {
			return;
		}
		Counter.builder("cluster_provisioning.pulumi.backup")
				.tags(Tags.of("result", result))
				.register(meterRegistry)
				.increment(count);
	}
}
