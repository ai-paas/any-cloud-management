package io.aipaas.cluster.agent.backup.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.runtime.HelmReleaseService;
import io.aipaas.cluster.agent.backup.port.BackupHistoryWriter;
import io.aipaas.cluster.agent.backup.node.EtcdBackupService;
import io.aipaas.cluster.agent.backup.node.NoOpBackupHistoryWriter;
import io.aipaas.cluster.agent.backup.node.PkiBackupService;
import io.aipaas.cluster.agent.backup.velero.BackupPolicyCatalog;
import io.aipaas.cluster.agent.backup.velero.BackupPolicyInstaller;
import io.aipaas.cluster.agent.backup.velero.VeleroBackupService;
import io.aipaas.cluster.agent.backup.velero.VeleroInstaller;
import io.aipaas.cluster.agent.backup.velero.VeleroRestoreService;
import io.aipaas.cluster.agent.backup.velero.VeleroScheduleService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link ClusterAgentBackupAutoConfiguration} wiring 회귀.
 *
 * <p>검증 포인트:
 * <ul>
 *   <li>AgentSessionRegistry + HelmReleaseService 가 있으면 모든 backup bean 자동 등록.</li>
 *   <li>각 feature toggle ({@code cluster-backup.{node,velero}.enabled=false}) 시
 *       해당 group 의 bean 만 미생성.</li>
 *   <li>BackupHistoryWriter 는 host bean 등록 시 NoOp default override.</li>
 *   <li>AgentSessionRegistry bean 부재 시 (ConditionalOnBean) 전체 wiring skip.</li>
 * </ul>
 */
class ClusterAgentBackupAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ClusterAgentBackupAutoConfiguration.class));

	private ApplicationContextRunner withAgentInfra() {
		// agent-starter 의 두 bean 만 mock 으로 주입 — 본 starter 의 @ConditionalOnBean(AgentSessionRegistry)
		// 와 Velero 의 HelmReleaseService 의존을 충족.
		return runner
				.withBean(AgentSessionRegistry.class, () -> mock(AgentSessionRegistry.class))
				.withBean(HelmReleaseService.class, () -> mock(HelmReleaseService.class));
	}

	@Test
	void defaultsRegistered_whenAgentInfraPresent() {
		withAgentInfra().run(ctx -> {
			assertThat(ctx)
					.hasSingleBean(EtcdBackupService.class)
					.hasSingleBean(PkiBackupService.class)
					.hasSingleBean(VeleroInstaller.class)
					.hasSingleBean(VeleroBackupService.class)
					.hasSingleBean(VeleroRestoreService.class)
					.hasSingleBean(VeleroScheduleService.class)
					.hasSingleBean(BackupPolicyCatalog.class)
					.hasSingleBean(BackupPolicyInstaller.class);
			// NoOp BackupHistoryWriter 가 default.
			assertThat(ctx.getBean(BackupHistoryWriter.class)).isInstanceOf(NoOpBackupHistoryWriter.class);
		});
	}

	@Test
	void wiringSkipped_whenNoAgentSessionRegistryBean() {
		// AgentSessionRegistry 없음 → @ConditionalOnBean 미충족 → AutoConfiguration 전체 skip.
		runner.run(ctx -> assertThat(ctx)
				.doesNotHaveBean(VeleroInstaller.class));
	}

	@Test
	void backupDisabled_skipsEtcdAndPki() {
		withAgentInfra()
				.withPropertyValues("cluster-backup.node.enabled=false")
				.run(ctx -> assertThat(ctx)
						.doesNotHaveBean(EtcdBackupService.class)
						.doesNotHaveBean(PkiBackupService.class)
						.hasSingleBean(VeleroInstaller.class));
	}

	@Test
	void veleroDisabled_skipsAllVeleroBeans() {
		withAgentInfra()
				.withPropertyValues("cluster-backup.velero.enabled=false")
				.run(ctx -> assertThat(ctx)
						.doesNotHaveBean(VeleroInstaller.class)
						.doesNotHaveBean(VeleroBackupService.class)
						.doesNotHaveBean(VeleroRestoreService.class)
						.doesNotHaveBean(VeleroScheduleService.class)
						.doesNotHaveBean(BackupPolicyCatalog.class)
						.doesNotHaveBean(BackupPolicyInstaller.class));
	}

	@Test
	void backupHistoryWriter_hostOverride_replacesNoOpDefault() {
		BackupHistoryWriter custom = new BackupHistoryWriter() {
			@Override public String start(BackupHistoryWriter.BackupStartRequest r) { return "id"; }
			@Override public void succeed(String id, BackupHistoryWriter.BackupSuccessReport r) {}
			@Override public void fail(String id, String e) {}
			@Override public Optional<BackupHistoryWriter.BackupRecord> findById(String id) {
				return Optional.empty();
			}
		};
		withAgentInfra()
				.withBean(BackupHistoryWriter.class, () -> custom)
				.run(ctx -> {
					assertThat(ctx).hasSingleBean(BackupHistoryWriter.class);
					assertThat(ctx.getBean(BackupHistoryWriter.class)).isSameAs(custom);
				});
	}
}
