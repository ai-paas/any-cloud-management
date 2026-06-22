package io.aipaas.cluster.agent.backup.velero;

/**
 * Velero CR (Backup/Restore/Schedule) 생성 결과.
 *
 * <p>CR 생성 직후 결과 — 실제 backup/restore 작업은 Velero controller 가 비동기로 실행.
 * 진행 상태는 caller 가 별도 GET_RESOURCE 로 polling (CR 의 status 필드).
 *
 * @param clusterName 대상 cluster
 * @param kind        CR kind ("Backup", "Restore", "Schedule")
 * @param name        CR metadata.name
 * @param namespace   CR metadata.namespace (보통 "velero")
 * @param phase       "Submitted" (방금 생성). Velero 가 InProgress / Completed / Failed 로 갱신.
 */
public record VeleroCrResult(
		String clusterName,
		String kind,
		String name,
		String namespace,
		String phase) {}
