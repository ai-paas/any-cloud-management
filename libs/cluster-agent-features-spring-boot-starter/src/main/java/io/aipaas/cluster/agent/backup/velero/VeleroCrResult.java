package io.aipaas.cluster.agent.backup.velero;

/**
 * Velero CR (Backup/Restore/Schedule) 생성 결과.
 *
 * @param clusterName 대상 cluster
 * @param kind        CR kind ("Backup", "Restore", "Schedule")
 * @param name        CR metadata.name
 * @param namespace   CR metadata.namespace (보통 "velero")
 * @param phase       "Submitted" (방금 생성). Velero 가 InProgress / Completed / Failed 로 갱신.
 */
public record VeleroCrResult(String clusterName, String kind, String name, String namespace, String phase) {}
