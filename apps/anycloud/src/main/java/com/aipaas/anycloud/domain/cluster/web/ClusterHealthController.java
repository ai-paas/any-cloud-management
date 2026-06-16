package com.aipaas.anycloud.domain.cluster.web;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.domain.cluster.ClusterFleetHealthService;
import com.aipaas.anycloud.domain.cluster.api.response.ClusterHealthResponse;
import com.aipaas.anycloud.domain.cluster.api.response.FleetAgentHealthResponse;
import io.aipaas.cluster.agent.runtime.AgentHealthService;
import io.aipaas.cluster.agent.runtime.ClusterHealth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cluster 종합 health endpoint. UI / 운영자가 cluster 사용 가능 여부를 한 번에 확인.
 *
 * <p>두 가지 view:
 * <ul>
 *   <li>{@code GET /v1/clusters/{clusterName}/health} — 단일 cluster 상세</li>
 *   <li>{@code GET /v1/agents/health} — fleet 전체 요약 + per-cluster list</li>
 * </ul>
 *
 * <p>모든 cluster 의 시계열 metric 은 {@code AgentHealthMetricsBinder} 가 Prometheus 로 노출
 * (anycloud_agent_* 시리즈). REST 응답은 snapshot 인스턴스 용.
 *
 * <p>fleet-wide 집계 logic 은 {@link ClusterFleetHealthService} 위임.
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Validated
@Tag(name = "Cluster Health (v1)", description = "Agent 기반 cluster 종합 health 점검")
public class ClusterHealthController {

    private final AgentHealthService agentHealthService;
    private final ClusterFleetHealthService fleetHealthService;

    @GetMapping("/clusters/{clusterName}/health")
    @Operation(
            summary = "Cluster 종합 health",
            description = "Agent status + stream 활성 + heartbeat 신선도 + K8s API 활성 종합. "
                    + "healthy=true 면 day-2 ops 즉시 가능. 실패 시 summary 로 원인 파악.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Health 정보 조회 성공 — healthy 필드 확인")})
    public ResponseEntity<ApiSuccessResponse<ClusterHealthResponse>> getHealth(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName) {
        ClusterHealth h = agentHealthService.getHealth(clusterName);
        ClusterHealthResponse dto = ClusterFleetHealthService.toDto(h);
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "Cluster health", dto));
    }

    /**
     * Fleet-wide health. 모든 등록 cluster 를 iterate 해 single-cluster endpoint 와 동일 DTO 의
     * 리스트 + 집계(total/healthy/unhealthy/noAgent) 반환.
     *
     * <p>cluster 수가 많을 때(1000+) 100건 단위 페이징 — service 가 처리.
     *
     * <p>HA: 한 cluster 에 여러 agent instance 있어도 {@link AgentHealthService#getHealth}
     * 가 {@code last_seen_at} 최신을 primary 로 선택 — fleet 응답은 cluster 단위 1 row.
     */
    @GetMapping("/agents/health")
    @Operation(
            summary = "Fleet-wide agent health 요약",
            description = "등록된 모든 cluster 의 agent 활성도. healthy/unhealthy/noAgent 집계 + "
                    + "status 분포 + per-cluster 상세. UI 대시보드용 snapshot — 시계열 metric 은 "
                    + "/actuator/prometheus 의 anycloud_agent_* 시리즈.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Fleet health 조회 성공")})
    public ResponseEntity<ApiSuccessResponse<FleetAgentHealthResponse>> getFleetHealth() {
        FleetAgentHealthResponse body = fleetHealthService.getFleetHealth();
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "Fleet agent health", body));
    }
}
