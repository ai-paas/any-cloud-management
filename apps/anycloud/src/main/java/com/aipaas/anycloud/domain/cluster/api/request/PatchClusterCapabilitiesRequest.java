package com.aipaas.anycloud.domain.cluster.api.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 운영자 수동 cluster capability override (sync).
 *
 * <p>{@link PatchClusterRequest} 가 비동기 cluster state 변경 (workerCount) 을 다루는 반면,
 * 본 request 는 sync flag — 즉시 effective. agent 의 자동 backfill (C5) 과 공존하며 마지막
 * 쓴 쪽이 이김 (다음 heartbeat 가 agent 측 값으로 덮어쓸 수 있음).
 *
 * <p>운영자가 명시적 override 가 필요한 경우 (예: GPU 노드가 부팅 직후라 agent 가 아직 감지 못 함) 사용.
 *
 * @param hasGpuNodes GPU 노드 포함 여부. null 이면 변경 안 함.
 */
@Schema(description = "Cluster capability flag 수동 설정 (sync, immediate effect)")
public record PatchClusterCapabilitiesRequest(
        @Schema(description = "GPU 노드 포함 여부 — true 면 dcgm-exporter 자동 설치 대상", example = "true") Boolean hasGpuNodes) {}
