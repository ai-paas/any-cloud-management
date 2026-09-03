package com.aipaas.anycloud.domain.agent.web;

import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.domain.agent.api.response.AdminAgentListResponse;
import com.aipaas.anycloud.domain.agent.internal.AdminAgentQueryService;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin fleet 페이지의 cluster-agent 전체 list endpoint. 기존 AdminAgentController 는
 * staleness 임계치만 다루므로 list 책임 분리 위해 신규 controller.
 */
@RestController
@RequestMapping("/v1/admin/agents")
@RequiredArgsConstructor
@Tag(name = "Admin Agents Fleet (v1)", description = "Fleet 페이지용 cluster-agent 전체 list")
public class AdminAgentListController {

    private final AdminAgentQueryService queryService;

    @GetMapping
    @Operation(
            summary = "Cluster-agent fleet list",
            description = "Admin 전용 — filter (status/clusterName/version/lastSeen) + server-side pagination.")
    public ResponseEntity<ApiSuccessResponse<AdminAgentListResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String clusterName,
            @RequestParam(required = false) String versionPrefix,
            @RequestParam(required = false) Long lastSeenOlderThanSec,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        AdminAgentListResponse body = queryService.query(
                parseStatuses(status), parseList(clusterName), versionPrefix, lastSeenOlderThanSec, page, size);
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "Admin agents loaded", body));
    }

    private static List<String> parseList(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static List<ClusterAgentStatus> parseStatuses(String csv) {
        return parseList(csv).stream()
                .map(AdminAgentListController::tryParseStatus)
                .filter(s -> s != null)
                .toList();
    }

    private static ClusterAgentStatus tryParseStatus(String raw) {
        try {
            return ClusterAgentStatus.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
