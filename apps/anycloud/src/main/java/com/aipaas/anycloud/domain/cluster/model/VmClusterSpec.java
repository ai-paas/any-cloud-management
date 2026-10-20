package com.aipaas.anycloud.domain.cluster.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * VM-source cluster spec (Pulumi provision).
 */
@Schema(description = "VM provisioning spec — Pulumi 가 새 cluster 를 생성")
public record VmClusterSpec(
        @Schema(
                        description = "CSP provider",
                        example = "aws",
                        allowableValues = {"aws", "gcp", "azure", "alibaba", "oci", "digitalocean", "openstack"})
                @NotBlank
                String provider,
        @Schema(description = "region", example = "ap-northeast-2") @NotBlank String region,
        @Schema(description = "환경 (dev/stage/prod)", example = "dev") String environment,
        @Schema(description = "사전 등록한 CSP credential id (선택 — 미지정 시 ENV)") String credentialId,
        @Schema(description = "Pulumi config map. 키는 'anycloud-k8s:xxx' 형식 또는 단순 키 (workerCount 등). 값은 모두 문자열.")
                Map<String, String> config,

        /** GPU 노드 포함 여부. true 이면 Pulumi 가 GPU flavor 의 워커 노드를 프로비저닝하고, agent ACTIVE 시 cluster-observability auto-installer 가 dcgm-exporter 자동 설치. */
        @Schema(
                        description = "GPU 노드 포함 cluster (Pulumi 가 GPU flavor 노드 프로비저닝 + dcgm-exporter 자동 설치)",
                        example = "false",
                        defaultValue = "false")
                Boolean hasGpuNodes,

        /**
         * spot / preemptible instance 를 씁니다. 비용은 크게 내려가지만 CSP 가 capacity 를
         * 회수하면 instance 가 꺼집니다. dev 나 CI workload 에만 쓰세요.
         *
         * <p>provider 별 매핑:
         * <ul>
         *   <li>AWS: spot instance request (EC2 spot)</li>
         *   <li>Azure: Spot priority VM (eviction policy=Deallocate)</li>
         *   <li>GCP: preemptible VM (spot instance)</li>
         *   <li>Alibaba: PostPaid 의 SpotStrategy=SpotAsPriceGo</li>
         *   <li>그 외 (OCI / OpenStack / DigitalOcean). no-op (지원 X 또는 의미 없음)</li>
         * </ul>
         */
        @Schema(
                        description = "Spot/preemptible instance 사용 (AWS/Azure/GCP). 비용 절감 vs 회수 위험.",
                        example = "false",
                        defaultValue = "false")
                Boolean useSpot,

        /**
         * OS image 지정. 미지정 시 provider 기본값 (대개 Ubuntu 24.04 LTS).
         *
         * <p>provider 별 형식:
         * <ul>
         *   <li>AWS: AMI ID (예: "ami-xxxxxxxx") 또는 AMI 이름</li>
         *   <li>GCP: Image family (예: "ubuntu-2404-lts") 또는 full image self-link</li>
         *   <li>Alibaba: ImageId</li>
         *   <li>OCI: Image OCID</li>
         *   <li>OpenStack: Image name 또는 UUID</li>
         *   <li>Proxmox: cloud 이미지 URL</li>
         *   <li>IBM: Image 이름</li>
         * </ul>
         */
        @Schema(
                        description = "OS image override (provider 별 ID 형식). 미지정 시 default (Ubuntu 22.04 LTS).",
                        example = "ami-0c55b159cbfafe1f0",
                        nullable = true)
                String osImage,

        /** 노드 root(boot) 디스크 크기 (GB). 미지정(null)/0 이하면 provider 별 기본값 (Go model.defaults 의 50GB) 적용. master/worker 동일. */
        @Schema(
                        description = "노드 root 디스크 크기(GB). 미지정 시 provider 기본 50GB. NodeHasDiskPressure 방지.",
                        example = "50",
                        nullable = true)
                Integer rootDiskSizeGb)
        implements ClusterSpec {

    // Compact constructor: null Boolean 은 false 로 정규화 (config map 매핑 단순화).
    public VmClusterSpec {
        if (hasGpuNodes == null) hasGpuNodes = false;
        if (useSpot == null) useSpot = false;
    }
}
