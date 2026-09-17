package com.aipaas.anycloud.domain.provisioning.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "VM 클러스터 상태 응답 DTO")
public class VmClusterStatusResponse {

    @Schema(description = "VM 클러스터 ID", example = "vmc-001")
    private String id;

    @Schema(description = "클러스터 이름", example = "demo-aws-01")
    private String clusterName;

    @Schema(description = "클라우드 제공자", example = "AWS")
    private String clusterProvider;

    @Schema(description = "VM 클러스터 상태", example = "READY")
    private String status;

    @Schema(description = "VM 클러스터 상태 설명", example = "Infrastructure and registration completed")
    private String statusDetail;

    @Schema(description = "현재 workflow 단계", example = "VERIFY")
    private String currentWorkflowStep;

    @Schema(description = "마지막 성공 단계", example = "BOOTSTRAP")
    private String lastSuccessfulStep;

    @Schema(description = "마지막 실패 단계", example = "VERIFY")
    private String lastFailedStep;

    @Schema(description = "workflow 재시도 횟수", example = "1")
    private Integer workflowRetryCount;

    @Schema(description = "Pulumi stack 이름", example = "demo-aws-01-dev")
    private String stackName;

    @Schema(description = "리전", example = "ap-northeast-2")
    private String region;

    @Schema(description = "환경", example = "dev")
    private String environment;

    /*
     * 이름은 요청 당시의 기록이라 자격증명이 지워지거나 개명돼도 남는다. id 를 함께 보내야 화면이
     * "아직 있는 자격증명" 과 "사라진 자격증명" 을 구분해 보여줄 수 있다.
     */
    @Schema(description = "연결된 자격증명 이름 (요청 당시 기록)", example = "aws-dev-credential")
    private String credentialName;

    @Schema(description = "연결된 자격증명 ID. 자격증명이 삭제됐으면 더는 조회되지 않는다", example = "cred-001")
    private String credentialId;

    @Schema(description = "등록 완료 여부", example = "true")
    private Boolean clusterRegistered;

    @Schema(description = "등록된 클러스터 식별자", example = "aipaas-aws-01")
    private String clusterId;

    @Schema(description = "마지막 오류 메시지", example = "")
    private String lastError;

    @Schema(description = "Bootstrap 및 kubelet 진단 로그")
    private String bootstrapLog;

    @Schema(description = "API 서버 URL", example = "https://3.39.10.20:6443")
    private String apiServerUrl;

    @Schema(description = "Master Public IP", example = "3.39.10.20")
    private String masterPublicIp;

    @Schema(description = "Master Public DNS", example = "ec2-3-39-10-20.ap-northeast-2.compute.amazonaws.com")
    private String masterPublicDns;

    @Schema(description = "Master VM 스펙", example = "t3.large")
    private String masterVmSpec;

    @Schema(description = "Worker VM 스펙", example = "t3.large")
    private String workerVmSpec;

    @Schema(description = "선택된 OS 이미지", example = "ubuntu-24.04")
    private String osImage;

    @Schema(description = "DB 엔드포인트", example = "")
    private String dbEndpoint;

    @Schema(description = "Kubeconfig fetch 명령")
    private String kubeconfigFetchCommand;

    @Schema(description = "Master SSH 명령")
    private String masterSshCommand;

    @Schema(description = "노드 목록")
    private List<VmClusterNodeResponse> nodes;

    @Schema(description = "구성 요소 상태 — DEGRADED 사유를 여기서 확인한다")
    private List<VmClusterComponentResponse> components;

    @Schema(description = "요청 addon 설치 상태")
    private List<VmClusterRequestedAddonResponse> requestedAddons;

    @Schema(description = "생성 시각", example = "2026-04-03T14:30:00")
    private LocalDateTime createdAt;

    @Schema(description = "마지막 변경 시각", example = "2026-04-03T14:36:00")
    private LocalDateTime updatedAt;

    @Schema(description = "요청 접수 시각", example = "2026-04-03T14:30:00")
    private LocalDateTime requestedAt;

    @Schema(description = "provision 시작 시각", example = "2026-04-03T14:30:05")
    private LocalDateTime provisioningStartedAt;

    @Schema(description = "bootstrap 시작 시각", example = "2026-04-03T14:33:00")
    private LocalDateTime bootstrappingStartedAt;

    @Schema(description = "verify 시작 시각", example = "2026-04-03T14:35:00")
    private LocalDateTime verifyingStartedAt;

    @Schema(description = "준비 완료 시각", example = "2026-04-03T14:36:00")
    private LocalDateTime readyAt;

    @Schema(description = "실패 시각", example = "2026-04-03T14:34:10")
    private LocalDateTime failedAt;

    @Schema(description = "삭제 시작 시각", example = "2026-04-03T15:00:00")
    private LocalDateTime deletingStartedAt;

    @Schema(description = "삭제 완료 시각", example = "2026-04-03T15:02:00")
    private LocalDateTime deletedAt;
}
