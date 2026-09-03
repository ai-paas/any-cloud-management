package com.aipaas.anycloud.domain.provisioning.api.response;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterPreflightIssue;
import com.aipaas.anycloud.domain.provisioning.pricing.CostEstimate;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
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
@Schema(description = "VM 클러스터 생성 사전 검토 응답 DTO")
public class VmClusterPreflightResponse {

    @Schema(description = "생성 가능 여부", example = "true")
    private boolean readyToProvision;

    @Schema(description = "동일 이름 클러스터 충돌 여부", example = "false")
    private boolean existingClusterConflict;

    @Schema(description = "정규화된 클라우드 제공자", example = "AWS")
    private String provider;

    @Schema(description = "클러스터 이름", example = "demo-aws-01")
    private String clusterName;

    @Schema(description = "배포 환경", example = "dev")
    private String environment;

    @Schema(description = "리전", example = "ap-northeast-2")
    private String region;

    @Schema(description = "예상 Pulumi stack 이름", example = "demo-aws-01-dev")
    private String stackName;

    @Schema(description = "사용할 자격증명 ID", example = "cred-001")
    private String credentialId;

    @Schema(description = "사용할 자격증명 이름", example = "aws-dev-credential")
    private String credentialName;

    @Schema(description = "자격증명 해석 성공 여부", example = "true")
    private boolean credentialResolved;

    @Schema(description = "필수 자격증명 key 목록", example = "[\"AWS_ACCESS_KEY_ID\",\"AWS_SECRET_ACCESS_KEY\"]")
    private List<String> requiredCredentialKeys;

    @Schema(description = "누락된 자격증명 key 목록", example = "[]")
    private List<String> missingCredentialKeys;

    @Schema(description = "VM Options live-query 확인 여부", example = "true")
    private boolean vmOptionsDiscoveryChecked;

    @Schema(description = "VM Options live-query 성공 여부", example = "true")
    private boolean vmOptionsDiscoveryReady;

    @Schema(description = "VM Options 조회 메시지", example = "[\"VM option discovery succeeded\"]")
    private List<String> vmOptionsDiscoveryMessages;

    @Schema(description = "Provider별 실행 준비 점검 수행 여부", example = "true")
    private boolean providerReadinessChecked;

    @Schema(description = "Provider별 실행 준비 상태", example = "true")
    private boolean providerReadinessReady;

    @Schema(
            description = "Provider별 실행 준비 메시지",
            example = "[\"OpenStack image/flavor and floating IP capacity should be verified before E2E\"]")
    private List<String> providerReadinessMessages;

    @Schema(
            description = "Provider별 E2E 준비 체크 항목",
            example =
                    "[\"Confirm rabbitmq/backend/worker are running\",\"Verify requested instance types are available in the selected region\"]")
    private List<String> e2eChecklistItems;

    @Schema(
            description = "기본값과 정규화가 적용된 config",
            example = "{\"masterInstanceType\":\"t3.large\",\"workerInstanceType\":\"t3.large\",\"workerCount\":\"2\"}")
    private Map<String, String> normalizedConfig;

    @Schema(description = "자동 적용된 기본값 목록", example = "[\"workerCount=2\",\"enableIngress=true\"]")
    private List<String> appliedDefaults;

    @Schema(description = "경고 목록", example = "[]")
    private List<String> warnings;

    @Schema(description = "오류 목록", example = "[]")
    private List<String> errors;

    @Schema(description = "구조화된 경고 목록")
    private List<VmClusterPreflightIssue> warningItems;

    @Schema(description = "구조화된 오류 목록")
    private List<VmClusterPreflightIssue> errorItems;

    /**
     * 정적 catalog 기반 예상 비용. on-demand list price (정확도 ±10-30%).
     * UNKNOWN provider 또는 catalog 누락 instance type 은 부분/null 응답 — frontend 가 status 필드로 분기.
     */
    @Schema(description = "예상 비용 (정확도 ±10-30%). 자세한 한계는 estimate.accuracyNote 참조.")
    private CostEstimate costEstimate;
}
