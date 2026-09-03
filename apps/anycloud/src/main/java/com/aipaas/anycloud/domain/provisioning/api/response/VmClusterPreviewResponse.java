package com.aipaas.anycloud.domain.provisioning.api.response;

import io.aipaas.cluster.provisioning.api.ProvisioningPreview;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * VM cluster create 사전 미리보기 응답 — {@code pulumi preview --json} 의 구조화 결과.
 */
@Schema(description = "Pulumi preview 결과 — 실제로 생성/변경될 CSP resource 계획")
public record VmClusterPreviewResponse(
        @Schema(description = "preview 대상 Pulumi stack 이름", example = "anycloud-AWS-dev-demo-aws-01") String stackName,
        @Schema(description = "stack 이 preview 이전부터 존재했는지 — false 면 신규 create 미리보기", example = "false")
                boolean stackExistedBefore,
        @Schema(description = "op 별 resource 수 (create/update/delete/same/replace ...)", example = "{\"create\": 14}")
                Map<String, Integer> changeSummary,
        @Schema(description = "same 외 변경이 하나라도 있는지", example = "true") boolean hasChanges,
        @Schema(description = "계획된 resource 단위 변경 목록") List<PlannedStep> steps) {

    @Schema(description = "계획된 단일 resource 변경")
    public record PlannedStep(
            @Schema(example = "create") String op,
            @Schema(example = "aws:ec2/instance:Instance") String type,
            @Schema(example = "demo-aws-01-master-0") String name) {}

    public static VmClusterPreviewResponse from(ProvisioningPreview result) {
        return new VmClusterPreviewResponse(
                result.stackName(),
                result.stackExistedBefore(),
                result.changeSummary(),
                result.hasChanges(),
                result.steps().stream()
                        .map(s -> new PlannedStep(s.op(), s.type(), s.name()))
                        .toList());
    }
}
