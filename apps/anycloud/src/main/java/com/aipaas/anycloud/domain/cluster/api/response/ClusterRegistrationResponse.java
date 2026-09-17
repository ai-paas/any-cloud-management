package com.aipaas.anycloud.domain.cluster.api.response;

import com.aipaas.anycloud.domain.cluster.model.BootstrapInfo;
import com.aipaas.anycloud.domain.operation.OperationResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** Cluster 등록 응답 wrapper. */
@Schema(description = "Cluster 등록 응답 — operation + agent bootstrap")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClusterRegistrationResponse(
        @Schema(description = "Lifecycle operation (state machine 추적용)") OperationResponse operation,
        @Schema(description = "Agent bootstrap (registered source 만). source=vm 은 null — VM 프로비저닝 완료 후 별도 endpoint.")
                BootstrapInfo bootstrap) {}
