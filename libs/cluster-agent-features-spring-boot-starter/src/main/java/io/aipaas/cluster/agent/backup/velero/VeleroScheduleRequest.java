package io.aipaas.cluster.agent.backup.velero;

/**
 * Velero Schedule CR 생성 요청.
 *
 * <p>Schedule CR 은 Velero 가 자체 cron loop 로 처리 — host 의 scheduler 부담 없음.
 *
 * <p>Velero docs: https://velero.io/docs/main/api-types/schedule/
 *
 * @param name           Schedule CR name. cluster 안에서 유니크.
 * @param namespace      Velero ns. default "velero".
 * @param cron           K8s cron expression (예: "0 2 * * *" = 매일 02:00 KST 가 아닌 UTC).
 * @param template       Schedule 이 생성할 Backup 의 template (cron 빈 다른 모든 필드).
 * @param paused         true 면 Velero 가 본 schedule trigger 안 함 (수동 일시정지).
 */
public record VeleroScheduleRequest(
		String name,
		String namespace,
		String cron,
		VeleroBackupRequest template,
		boolean paused) {

	public static VeleroScheduleRequest dailyFull(String name, String cron) {
		return new VeleroScheduleRequest(name, "velero", cron,
				VeleroBackupRequest.fullCluster(name + "-template"), false);
	}
}
