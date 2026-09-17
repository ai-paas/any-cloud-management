package com.aipaas.anycloud.domain.cluster.model;

import com.aipaas.anycloud.domain.addon.model.AddonSpec;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** Registered-source cluster spec (외부 K8s cluster 등록). */
@Schema(description = "외부 cluster 등록 spec — metadata 만 (인증/URL 입력 없음, agent-led registration)")
public record RegisteredClusterSpec(
        @Schema(description = "CSP provider", example = "AWS") @NotBlank String provider,
        @Schema(description = "cluster type (EKS / GKE / AKS / Self-managed ...)", example = "EKS") String clusterType,
        @Schema(description = "설명") String description,

        /**
         * 이 외부 cluster 가 GPU 노드를 가지는지. true 면 agent dial-in + observability 자동 설치 시점에
         * dcgm-exporter 도 함께 설치. null/false 면 일반. Agent 가 K8s 노드 검사로 자동 감지해 backfill.
         */
        @Schema(
                        description = "GPU 노드 포함 (auto-installer 가 dcgm-exporter 추가 설치)",
                        example = "false",
                        defaultValue = "false")
                Boolean hasGpuNodes,

        /** cluster 생성 시 자동 설치할 addon 목록. cluster 가 ACTIVE 로 전환되면 background workflow (RabbitMQ) 가 각 addon 을 helm install. null/empty 면 addon 없음. */
        @Schema(description = "Optional addons to install after cluster ACTIVE") List<AddonSpec> addons)
        implements ClusterSpec {}
