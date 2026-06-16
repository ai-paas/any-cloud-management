package com.aipaas.anycloud.common.web;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "액션형 성공 응답 데이터")
public class ActionResponse {

    @Schema(description = "대상 리소스 유형", example = "vmCluster")
    String resourceType;

    @Schema(description = "대상 리소스 식별자", example = "demo-aws-01")
    String resourceId;

    @Schema(description = "수행된 작업", example = "create")
    String operation;

    @Schema(description = "작업 상태", example = "ACCEPTED")
    String state;
}
