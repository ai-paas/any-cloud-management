package com.aipaas.anycloud.domain.cluster.model;

import com.aipaas.anycloud.domain.addon.model.AddonSpec;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Registered-source cluster spec (외부 K8s cluster 등록).
 *
 * <p>Cluster registration 단순화. Body 가 metadata 만 (provider/type/description/gpu).
 * 기존 apiServerUrl / serverCA / clientCA / clientKey / clientToken / monitServerURL 은 모두 제거.
 *
 * <p>Cluster 등록 후 응답에 박힌 {@code bootstrap.helmInstallCommand} 또는
 * {@code GET /v1/clusters/{id}/agent-manifest.yaml} 을 사용자가 자신의 kubectl context 에서 직접
 * 실행해 cluster-agent 를 install (ArgoCD/Flux/OCM 표준 pull-based pattern). Agent 가 backend 로
 * dial 하면 cluster 가 PENDING_AGENT → ACTIVE 전환.
 */
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

        /**
         * cluster 생성 시 자동 설치할 addon 목록. cluster 가 ACTIVE 로 전환되면
         * background workflow (RabbitMQ) 가 각 addon 을 helm install. null/empty 면 addon 없음.
         *
         * <p>각 element 는 catalog ref 또는 custom spec — {@link AddonSpec} 참조.
         */
        @Schema(description = "Optional addons to install after cluster ACTIVE") List<AddonSpec> addons)
        implements ClusterSpec {}
