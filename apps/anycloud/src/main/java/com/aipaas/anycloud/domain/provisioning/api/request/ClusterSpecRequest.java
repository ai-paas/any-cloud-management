package com.aipaas.anycloud.domain.provisioning.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * CSP 무관 클러스터 사양.
 *
 * <p>7개 CSP 전부에 대응물이 있는 값만 담는다. 어느 한쪽에만 있는 값은 {@code providerSpec} 으로
 * 간다 — 공통 스키마를 넓히면 provider 하나가 늘 때마다 계약이 흔들린다.
 */
@Schema(description = "CSP 무관 클러스터 사양")
public record ClusterSpecRequest(
        @Schema(description = "Kubernetes 버전", example = "1.31") String kubernetesVersion,
        @Min(1) @Max(7) @Schema(description = "control-plane 수. 짝수면 홀수로 올림", example = "1") Integer masterCount,
        @Min(0) @Max(50) @Schema(description = "worker 수", example = "2") Integer workerCount,
        @Schema(description = "master 인스턴스 타입. Proxmox 는 \"코어-메모리MiB\"", example = "4-8-50") String masterInstanceType,
        @Schema(description = "worker 인스턴스 타입", example = "4-8-50") String workerInstanceType,
        @Min(20) @Max(2000) @Schema(description = "루트 디스크 GB", example = "50") Integer rootDiskSizeGb,
        @Schema(description = "OS 이미지. 표현이 CSP 마다 다르다 — OCI 는 OCID, Azure 는 4단 좌표", example = "ubuntu-24.04")
                String osImage,
        @Schema(description = "SSH 사용자", example = "ubuntu") String sshUser,
        @Valid @Schema(description = "네트워크 대역") NetworkSpecRequest network,
        @Schema(description = "ingress controller 설치", example = "true") Boolean enableIngress,
        @Schema(description = "GPU operator 설치", example = "false") Boolean enableGpuOperator,
        @Schema(description = "spot/preemptible 인스턴스 사용", example = "false") Boolean useSpot) {

    /** 네트워크 대역. 셋이 겹치면 파드나 서비스 트래픽이 엉뚱한 곳으로 간다. */
    @Schema(description = "네트워크 대역")
    public record NetworkSpecRequest(
            @Schema(description = "VPC CIDR. Proxmox 는 쓰지 않는다", example = "10.90.0.0/24") String vpcCidr,
            @Schema(description = "파드 CIDR", example = "10.244.0.0/16") String podCidr,
            @Schema(description = "서비스 CIDR", example = "10.96.0.0/12") String serviceCidr) {}
}
