package io.aipaas.cluster.provisioning.core;

/**
 * Pulumi state backup 정책의 host-agnostic 추상화.
 *
 * <p>anycloud 의 {@code PulumiBackupProperties}(prefix {@code pulumi.backup})는 application.yml 의 자체
 * prefix 와 결합되어 starter 로 직접 가져올 수 없다. host 가 이 interface 를 자신의 config 로부터 채워
 * 노출하면 starter 의 {@code PulumiStateBackupScheduler}/{@code Validator} 가 그 값을 사용한다.
 *
 * <p>Default impl: {@link DefaultPulumiBackupPropertiesProvider} — backup 비활성. host 가 자체 bean
 * 등록 시 자동 override.
 */
public interface PulumiBackupPropertiesProvider {

	/** Backup 기능 enabled. false 면 scheduler 가 no-op. */
	boolean isEnabled();

	/** Backup 파일 저장 디렉토리. */
	String getDirectory();

	/** 보존 기간(일). 초과 파일 자동 삭제. */
	int getRetentionDays();

	/** 복구 dry-run 검증 enabled. false 면 validator 가 no-op. */
	boolean isRestoreDryRunEnabled();

	/** 복구 dry-run 시 backup 파일에 존재해야 할 최소 resource 수. */
	int getMinResourceCount();

	/** deep validation — 임시 stack 에 실제 import 라운드트립 검증 수행 여부. */
	boolean isDeepValidation();

	/**
	 * Default — backup 비활성. host 가 자체 bean 등록 안 하면 본 impl 사용 → scheduler/validator 가
	 * 정상 boot 하되 cron 시점에 no-op.
	 */
	class DefaultPulumiBackupPropertiesProvider implements PulumiBackupPropertiesProvider {
		@Override public boolean isEnabled() { return false; }
		@Override public String getDirectory() { return "/var/lib/cluster-provisioning/pulumi-backups"; }
		@Override public int getRetentionDays() { return 14; }
		@Override public boolean isRestoreDryRunEnabled() { return false; }
		@Override public int getMinResourceCount() { return 1; }
		@Override public boolean isDeepValidation() { return false; }
	}
}
