package io.aipaas.cluster.agent.backup.velero;

/**
 * bundled Velero Schedule 정책의 catalog entry.
 *
 * <p>{@link BackupPolicyCatalog} 가 classpath:velero-policies/*.yaml 을 로드해 본 record 로 보관.
 * id 는 파일명 (확장자 제외) — UI / log / API 에서 stable identifier.
 *
 * <p>manifestYaml 은 그대로 cluster-agent 의 APPLY_MANIFEST 에 전달되는 Velero Schedule CR.
 * {@code ${NAMESPACE}} placeholder 가 install 시 치환된다 (Velero ns 보통 "velero").
 *
 * @param id           파일명 (예: "daily-full-cluster")
 * @param displayName  UI 표시용 한글 라벨
 * @param description  본 정책이 다루는 영역 요약
 * @param schedule     cron expression (예: "0 2 * * *") — UI preview 용
 * @param ttl          backup 보존 기간 — UI preview 용 (예: "720h0m0s")
 * @param manifestYaml Velero Schedule CR YAML (placeholder 포함)
 */
public record BackupPolicy(
		String id,
		String displayName,
		String description,
		String schedule,
		String ttl,
		String manifestYaml) {}
