package io.aipaas.cluster.agent.backup.velero;

import java.util.List;

/**
 * bundled Velero 정책을 cluster 에 설치.
 *
 * <p>{@link BackupPolicyCatalog} 의 정책 (id 기반) 을 Velero Schedule CR 로 APPLY_MANIFEST.
 * Velero controller 가 본 Schedule 의 cron 으로 backup 자동 실행 — anycloud 의 scheduler 부담 없음.
 */
public interface BackupPolicyInstaller {

	/**
	 * 단일 정책 설치.
	 *
	 * @param clusterName 대상 cluster
	 * @param policyId    {@link BackupPolicy#id()}
	 * @param namespace   Velero ns (보통 "velero"). null 이면 default.
	 * @throws io.aipaas.cluster.agent.backup.core.BackupException catalog 에 id 없으면 INVALID_PARAMS
	 */
	BackupPolicyApplyResult install(String clusterName, String policyId, String namespace);

	/** 카탈로그 전체 설치. 일부 실패해도 나머지 시도 — 결과 list 의 status 로 분기. */
	List<BackupPolicyApplyResult> installAll(String clusterName, String namespace);

	/** 단일 정책 제거 (Velero Schedule CR 삭제). */
	BackupPolicyApplyResult uninstall(String clusterName, String policyId, String namespace);
}
