package io.aipaas.cluster.agent.backup.autoconfigure;
import io.aipaas.cluster.agent.observability.port.ClusterCatalog;

import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.runtime.HelmReleaseService;
import io.aipaas.cluster.agent.backup.port.BackupHistoryWriter;
import io.aipaas.cluster.agent.backup.node.EtcdBackupService;
import io.aipaas.cluster.agent.backup.node.NoOpBackupHistoryWriter;
import io.aipaas.cluster.agent.backup.node.PkiBackupService;
import io.aipaas.cluster.agent.backup.node.impl.EtcdBackupServiceImpl;
import io.aipaas.cluster.agent.backup.node.impl.PkiBackupServiceImpl;
import io.aipaas.cluster.agent.backup.velero.BackupPolicyCatalog;
import io.aipaas.cluster.agent.backup.velero.BackupPolicyInstaller;
import io.aipaas.cluster.agent.backup.velero.VeleroBackupService;
import io.aipaas.cluster.agent.backup.velero.VeleroInstaller;
import io.aipaas.cluster.agent.backup.velero.VeleroRestoreService;
import io.aipaas.cluster.agent.backup.velero.VeleroScheduleService;
import io.aipaas.cluster.agent.backup.velero.impl.BackupPolicyInstallerImpl;
import io.aipaas.cluster.agent.backup.velero.impl.VeleroBackupServiceImpl;
import io.aipaas.cluster.agent.backup.velero.impl.VeleroInstallerImpl;
import io.aipaas.cluster.agent.backup.velero.impl.VeleroRestoreServiceImpl;
import io.aipaas.cluster.agent.backup.velero.impl.VeleroScheduleServiceImpl;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * cluster-backup starter auto-config.
 *
 * <p>활성 조건: cluster-agent-starter 가 제공하는 {@link AgentSessionRegistry} bean 이 존재.
 * 백업 작업은 항상 명시적 cluster name 으로 호출되므로 ClusterCatalog 같은 fan-out SPI 는 불필요
 * (backup-starter 가 observability-starter 에 강결합되지 않도록).
 *
 * <p>모든 bean 은 {@link ConditionalOnMissingBean} 으로 override 가능.
 */
@AutoConfiguration
@ConditionalOnBean(AgentSessionRegistry.class)
@EnableConfigurationProperties(BackupProperties.class)
public class ClusterAgentBackupAutoConfiguration {

	/**
	 * BackupHistory SPI default. host backend (e.g. anycloud) 가 DB-backed impl 등록하면 자동
	 * override. 미등록 시 NoOp — backup 자체는 정상 수행, history 만 미기록. production 환경에선 host 가 반드시
	 * 자체 impl 등록 권장 (startup log 로 안내).
	 */
	@Bean
	@ConditionalOnMissingBean
	public BackupHistoryWriter backupHistoryWriter() {
		return new NoOpBackupHistoryWriter();
	}

	// etcd / PKI backup.
	//
	// timeout 은 etcd 의 size 에 비례 — 작은 cluster (~50MB snapshot) 는 1분, 큰 cluster 는 더 길게.
	// 본 default 는 5분 — properties 의 cluster-backup.backup.* 으로 override 가능.

	/** etcd/PKI backup 끄려면 {@code cluster-backup.backup.enabled=false}. */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "cluster-backup.node", name = "enabled", matchIfMissing = true)
	public EtcdBackupService etcdBackupService(
			AgentSessionRegistry sessionRegistry, BackupProperties props) {
		return new EtcdBackupServiceImpl(sessionRegistry, Duration.ofMinutes(5));
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "cluster-backup.node", name = "enabled", matchIfMissing = true)
	public PkiBackupService pkiBackupService(
			AgentSessionRegistry sessionRegistry, BackupProperties props) {
		return new PkiBackupServiceImpl(sessionRegistry, Duration.ofMinutes(2));
	}

	// Velero installer + Backup/Restore/Schedule services.
	//
	// VeleroInstaller 는 cluster-agent-starter 의 HelmReleaseService 를 그대로 활용 — 새 RPC 추가 없이
	// 기존 INSTALL_ADDON 으로 velero 차트 설치. Backup/Restore/Schedule 은 APPLY_MANIFEST 로 Velero
	// CR 생성. Velero controller 가 비동기 실행 — host 가 status polling.

	/** Velero 안 쓰면 {@code cluster-backup.velero.enabled=false} 로 일괄 비활성. */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "cluster-backup.velero", name = "enabled", matchIfMissing = true)
	public VeleroInstaller veleroInstaller(
			HelmReleaseService helmReleaseService,
			BackupPolicyInstaller backupPolicyInstaller,
			BackupProperties props) {
		return new VeleroInstallerImpl(
				helmReleaseService,
				backupPolicyInstaller,
				props.velero().autoInstallPolicies());
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "cluster-backup.velero", name = "enabled", matchIfMissing = true)
	public VeleroBackupService veleroBackupService(AgentSessionRegistry sessionRegistry) {
		return new VeleroBackupServiceImpl(sessionRegistry);
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "cluster-backup.velero", name = "enabled", matchIfMissing = true)
	public VeleroRestoreService veleroRestoreService(AgentSessionRegistry sessionRegistry) {
		return new VeleroRestoreServiceImpl(sessionRegistry);
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "cluster-backup.velero", name = "enabled", matchIfMissing = true)
	public VeleroScheduleService veleroScheduleService(AgentSessionRegistry sessionRegistry) {
		return new VeleroScheduleServiceImpl(sessionRegistry);
	}

	// Bundled Velero policy catalog + auto-install hook.

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "cluster-backup.velero", name = "enabled", matchIfMissing = true)
	public BackupPolicyCatalog backupPolicyCatalog() {
		return new BackupPolicyCatalog();
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "cluster-backup.velero", name = "enabled", matchIfMissing = true)
	public BackupPolicyInstaller backupPolicyInstaller(
			AgentSessionRegistry sessionRegistry, BackupPolicyCatalog catalog) {
		return new BackupPolicyInstallerImpl(sessionRegistry, catalog);
	}
}
