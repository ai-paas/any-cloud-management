package com.aipaas.anycloud.domain.provisioning.model;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "VM 클러스터 사전 검토 이슈 DTO")
public class VmClusterPreflightIssue {

    @Schema(description = "이슈 코드", example = "MISSING_CREDENTIALS")
    private String code;

    @Schema(
            description = "이슈 메시지",
            example = "Provider credential is incomplete, so E2E validation will stop before provisioning")
    private String message;

    @Schema(description = "관련 대상 필드", example = "credentialId")
    private String field;
}
