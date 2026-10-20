package com.aipaas.anycloud.domain.provisioning.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * CSP 무관 클러스터 사양.
 *
 * <p>7개 CSP 전체에 대응값이 존재하는 항목만 포함. 일부 CSP 전용 값은 {@code providerSpec} 으로
 * 분리. 공통 스키마 확장 시 provider 추가마다 계약 변경.
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
        @Schema(description = "모니터링 스택(Prometheus) 설치. 비우면 설치한다", example = "true") Boolean enableMonitoring,
        @Schema(description = "spot/preemptible 인스턴스 사용", example = "false") Boolean useSpot) {

    /** 네트워크 대역. 3개 대역이 겹치면 파드, 서비스 트래픽이 잘못된 경로로 전달. */
    @Schema(description = "네트워크 대역")
    public record NetworkSpecRequest(
            @Schema(description = "VPC CIDR. Proxmox 는 쓰지 않는다", example = "10.90.0.0/24") String vpcCidr,
            @Schema(description = "파드 CIDR", example = "10.244.0.0/16") String podCidr,
            @Schema(description = "서비스 CIDR", example = "10.96.0.0/12") String serviceCidr) {}
}
