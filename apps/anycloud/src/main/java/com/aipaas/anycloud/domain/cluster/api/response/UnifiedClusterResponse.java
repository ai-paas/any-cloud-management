package com.aipaas.anycloud.domain.cluster.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Builder;

/**
 * 통합 cluster 응답. source 가 vm 인지 registered 인지를 동일한 schema 로 표현.
 *
 * <p><b>Deprecated</b> — VM 인프라 자원과 K8s cluster 자원이 별도 API namespace 로 분리됐다 ({@code /v1/vms},
 * {@code /v1/clusters}). 신규 caller 는 {@code VmClusterListItemResponse} / {@code VmClusterStatusResponse}
 * (VM 측) 또는 cluster 전용 응답 (registered side) 을 사용. 본 통합 응답은 backward-compat 유지 동안만 존속.
 */
@Deprecated
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "통합 cluster 응답 (vm + registered 공통 schema) — deprecated, /v1/vms 와 /v1/clusters 분리")
@Builder
public record UnifiedClusterResponse(
        @Schema(
                        description = "source",
                        allowableValues = {"vm", "registered"},
                        example = "vm")
                String source,
        @Schema(description = "클러스터 이름", example = "demo-aws-01") String clusterName,
        @Schema(
                        description = "연결된 VM 자원의 이름 (1:1). null 이면 manual 등록 cluster. VM provisioning 으로 "
                                + "만들어진 cluster 는 동일 이름이 vm_cluster 테이블에도 존재 — UI 가 VM 메뉴로 cross-link 시 사용.",
                        example = "demo-aws-01")
                String linkedVmName,
        @Schema(description = "provider", example = "AWS") String provider,
        @Schema(description = "region", example = "ap-northeast-2") String region,
        @Schema(description = "environment", example = "dev") String environment,
        @Schema(description = "상태 (PROVISIONING / READY / FAILED / BLOCKED / DELETING / DELETED / IMPORTED)")
                String status,
        @Schema(description = "VM cluster 의 워커 수 (registered 면 null)") Integer workerCount,
        @Schema(description = "생성 시각") LocalDateTime createdAt,
        @Schema(description = "READY 도달 시각 (해당 시)") LocalDateTime readyAt,
        @Schema(description = "마지막 에러 메시지 (있을 때만)") String lastError,
        @Schema(
                        description = "GPU 노드 포함 여부. cluster-observability 가 dcgm-exporter "
                                + "자동 설치 여부 결정에 활용. agent 가 자동 backfill 가능.",
                        example = "false")
                Boolean hasGpuNodes,
        @Schema(
                        description = "cluster-agent 연결 상태 (K8s status 와 별개 — registered source 에서만 채워짐). "
                                + "CONNECTED=정상 / DEGRADED=stream 활성이지만 heartbeat stale / "
                                + "DISCONNECTED=stream 끊김 또는 agent REVOKED/FAILED / NOT_REGISTERED=agent 등록 안됨.",
                        allowableValues = {"CONNECTED", "DEGRADED", "DISCONNECTED", "NOT_REGISTERED"},
                        example = "CONNECTED")
                String agentConnectivity,
        @Schema(description = "마지막 agent heartbeat 으로부터 경과 초. null=한 번도 본 적 없음.", example = "8")
                Long agentHeartbeatSecondsAgo,
        @Schema(
                        description = "agent health 요약 (사람이 읽을 수 있는 한 줄 — UI tooltip 용).",
                        example = "stream up, heartbeat 8s ago")
                String agentHealthSummary,
        @Schema(
                        description = "VM cluster workflow 진행 단계 (vm source 만 — registered 면 null). "
                                + "UI 가 PROVISIONING 같은 단일 status 대신 step 별 progress 노출 시 사용.")
                WorkflowProgress workflowProgress) {

    /**
     * VM cluster 의 workflow 단계 + percent. lastSuccessfulStep 기반 — currentStep 은 "지금 진행 중"
     * 의미가 강해 step service 의 인생 주기와 race 가능. lastSuccessfulStep 은 commit 된 사실.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Workflow step 진행 상태")
    @Builder
    public record WorkflowProgress(
            @Schema(
                            description = "현재 진행 중 단계 (state machine 의 in-flight). null=아직 시작 안 함 또는 terminal.",
                            allowableValues = {"PROVISION", "BOOTSTRAP", "VERIFY", "DESTROY"},
                            example = "PROVISION")
                    String currentStep,
            @Schema(description = "마지막으로 완료된 단계 (영속화된 사실).", example = "PROVISION") String lastSuccessfulStep,
            @Schema(
                            description = "전체 진행률 (0~100). lastSuccessfulStep 기반 계산. PROVISION 완료=33, "
                                    + "BOOTSTRAP 완료=66, VERIFY 완료=100. READY 도달 = 100.",
                            example = "33")
                    Integer percent,
            @Schema(
                            description = "현재 step 시작 시각 (provisioning_started_at / bootstrapping_started_at / "
                                    + "verifying_started_at 중 currentStep 에 해당하는 timestamp).",
                            example = "2026-06-11T15:43:30")
                    LocalDateTime stepStartedAt,
            @Schema(description = "workflow 재시도 횟수 (PROVISION step 실패 후 retry 누적).", example = "0") Integer retryCount,
            @Schema(
                            description = "BOOTSTRAP 단계 내부 sub-step. BOOTSTRAP 은 20~30분 걸려 "
                                    + "큰 단계만으론 'stuck' 인식 — 어느 sub-step 인지 노출. null=BOOTSTRAP 아님 또는 완료.",
                            allowableValues = {
                                "BOOTSTRAP_NODE_PREPARATION",
                                "BOOTSTRAP_MASTER_INIT",
                                "BOOTSTRAP_EXTRA_MASTER_JOIN",
                                "BOOTSTRAP_WORKER_JOIN",
                                "BOOTSTRAP_NODES_READY",
                                "BOOTSTRAP_ADDONS"
                            },
                            example = "BOOTSTRAP_MASTER_INIT")
                    String subStep,
            @Schema(description = "현재 sub-step 시작 시각 — 'N분째 이 단계' 진단용.", example = "2026-06-11T15:45:10")
                    LocalDateTime subStepStartedAt,
            @Schema(
                            description =
                                    "마지막 실패 분류 코드 — ErrorResponse.code 와 동일 체계. " + "UI 가 lastError 메시지 대신 코드로 분기 가능.",
                            example = "UPSTREAM_FAILED")
                    String lastErrorCode) {}
}
