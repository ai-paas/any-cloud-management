package com.aipaas.anycloud.domain.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditLogResponse {

    @Schema(description = "audit log id (UUID)")
    private String id;

    @Schema(description = "동일 요청 그룹핑 (X-Request-Id)")
    private String requestId;

    @Schema(description = "수행 주체 (gateway 가 forward 한 X-User-Id)")
    private String principal;

    @Schema(description = "클라이언트 IP")
    private String clientIp;

    @Schema(description = "HTTP 메서드", example = "POST")
    private String httpMethod;

    @Schema(description = "요청 path", example = "/vm/clusters/demo-aws-01/scale")
    private String path;

    @Schema(description = "표준 action 명", example = "vmcluster.scaleVmCluster")
    private String action;

    @Schema(description = "리소스 타입", example = "vmCluster")
    private String resourceType;

    @Schema(description = "리소스 식별자", example = "demo-aws-01")
    private String resourceId;

    @Schema(description = "응답 HTTP status")
    private Integer statusCode;

    @Schema(description = "처리 소요(ms)")
    private Long durationMs;

    @Schema(description = "에러 메시지 (있을 때만)")
    private String errorMessage;

    @Schema(description = "발생 시각")
    private LocalDateTime createdAt;
}
