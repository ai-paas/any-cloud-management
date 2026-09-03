package io.aipaas.cluster.agent.backup.velero;

/**
 * Velero Schedule CR 생성 service.
 *
 * <p>Velero 가 자체 cron 으로 본 schedule 의 backup 을 trigger — host 는 schedule 생성 / 일시정지 /
 * 삭제만 책임.
 */
public interface VeleroScheduleService {

	VeleroCrResult create(String clusterName, VeleroScheduleRequest request);
}
