package com.aipaas.anycloud.domain.provisioning.web;

import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.domain.provisioning.properties.VmClusterStateMachineProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoint — runtime 의 state machine 정의 조회 + strict toggle.
 *
 * <p>운영자가 현재 deployed 버전의 state machine graph 를 query 가능 — doc 의 stale 위험 회피.
 * 또한 새 status 도입 시 별도 reload 없이 self-documenting (서버 응답이 진실의 원천).
 *
 * <p>Endpoint:
 * <ul>
 *   <li>{@code GET /v1/admin/state-machine/vmcluster} — VmClusterStatus enum 의 전체 transition graph</li>
 *   <li>{@code GET /v1/admin/state-machine/vmcluster/strict} — 현재 strict toggle 상태</li>
 *   <li>{@code POST /v1/admin/state-machine/vmcluster/strict} — strict toggle 변경 (재시작 없이)</li>
 * </ul>
 *
 * <p>응답 schema (graph):
 * <pre>
 * {
 *   "states": [
 *     { "name": "REQUESTED", "terminal": false, "blocked": false, "inProgress": true,
 *       "transitions": ["PROVISIONING", "FAILED", "BLOCKED", "DELETING"] },
 *     ...
 *   ],
 *   "mermaid": "stateDiagram-v2\n  ..."     // optional, GitHub 등 가시화 도구 호환
 * }
 * </pre>
 *
 * <p><b>Strict toggle 주의</b>: POST 응답이 200 이라도 변경은 본 instance 의 in-memory singleton
 * {@link VmClusterStateMachineProperties} 만 갱신됨. 다중 instance 운영 시 모든 instance 에 동일하게
 * 호출 필요 — 영구 적용은 application.yml 의 {@code anycloud.vm-cluster.state-machine.strict}
 * 값 변경 후 재배포. 본 endpoint 는 emergency rollback / canary 검증용.
 */
@RestController
@RequestMapping("/v1/admin/state-machine")
@RequiredArgsConstructor
@Tag(name = "Admin (state machine)", description = "운영자용 state machine 정의 조회 + strict toggle")
public class AdminStateMachineController {

    private static final Logger log = LoggerFactory.getLogger(AdminStateMachineController.class);

    private final VmClusterStateMachineProperties stateMachineProperties;

    @GetMapping("/vmcluster")
    @Operation(
            summary = "VmCluster state machine graph (runtime)",
            description = "현재 deployed 코드의 VmClusterStatus enum + canTransitionTo graph 를 JSON 으로 반환. "
                    + "doc stale 회피용. UI / monitoring tool 가 이 응답으로 visualization 가능.")
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> vmClusterStateMachine() {
        List<Map<String, Object>> states = new ArrayList<>();
        for (VmClusterStatus s : VmClusterStatus.values()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", s.name());
            node.put("terminal", s.isTerminal());
            node.put("blocked", s.isBlocked());
            node.put("inProgress", s.isInProgress());
            node.put("detailMessage", s.detailMessage());

            List<String> transitions = new ArrayList<>();
            for (VmClusterStatus next : VmClusterStatus.values()) {
                if (next != s && s.canTransitionTo(next)) {
                    transitions.add(next.name());
                }
            }
            node.put("transitions", transitions);
            states.add(node);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("states", states);
        body.put("mermaid", buildMermaidDiagram());
        body.put("strict", stateMachineProperties.isStrict());

        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "VmCluster state machine graph", body));
    }

    @GetMapping("/vmcluster/strict")
    @Operation(
            summary = "VmCluster strict toggle 조회",
            description = "현재 instance 의 strict mode 활성 여부 (true=invalid transition 시 throw, "
                    + "false=observation mode + log.warn).")
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> getStrictMode() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("strict", stateMachineProperties.isStrict());
        body.put(
                "description",
                stateMachineProperties.isStrict()
                        ? "Strict mode — invalid transition 시 IllegalStateException throw"
                        : "Observation mode — invalid transition 시 log.warn + audit row + apply");
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "VmCluster strict mode", body));
    }

    @PostMapping("/vmcluster/strict")
    @Operation(
            summary = "VmCluster strict toggle 변경 (in-memory)",
            description = "본 instance 의 strict mode 즉시 변경. 재시작 없이 적용되나, 다중 instance 운영 시 "
                    + "모든 instance 에 호출 필요. 영구 적용은 application.yml 변경 후 재배포.")
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> setStrictMode(
            @RequestBody @NotNull StrictModeRequest req) {
        boolean previous = stateMachineProperties.isStrict();
        boolean next = req.strict();
        stateMachineProperties.setStrict(next);

        // audit log — 운영자 mode 전환 기록 (Spring Security context 활성 시
        // SecurityContextHolder 통해 추가 가능 — 현재는 caller IP 만 servlet container 가 access log 에 남김).
        log.warn("VmCluster state-machine STRICT MODE TOGGLE: {} → {} (in-memory, instance-local)", previous, next);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("previous", previous);
        body.put("current", next);
        body.put(
                "warning",
                "in-memory only — multi-instance 운영 시 모든 instance 에 동일 호출 필요. " + "영구 적용은 application.yml 변경 후 재배포.");
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "VmCluster strict mode updated", body));
    }

    private String buildMermaidDiagram() {
        StringBuilder sb = new StringBuilder("stateDiagram-v2\n");
        for (VmClusterStatus s : VmClusterStatus.values()) {
            for (VmClusterStatus next : VmClusterStatus.values()) {
                if (next != s && s.canTransitionTo(next)) {
                    sb.append("    ")
                            .append(s.name())
                            .append(" --> ")
                            .append(next.name())
                            .append("\n");
                }
            }
        }
        return sb.toString();
    }

    /** Strict mode toggle 요청 body. */
    public record StrictModeRequest(boolean strict) {}
}
