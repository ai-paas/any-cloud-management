package io.aipaas.cluster.agent.backup.velero;

/**
 * Velero Backup CR 생성 service.
 *
 * <p>1회성 backup 을 trigger — Velero controller 가 비동기 실행. 결과는 Backup CR 의 status 필드.
 * 정기 backup 은 {@link VeleroScheduleService} 사용 (Velero 가 자체 cron 구현).
 */
public interface VeleroBackupService {

	VeleroCrResult create(String clusterName, VeleroBackupRequest request);
}
