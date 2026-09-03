package com.aipaas.anycloud.domain.agent.web;

import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import io.aipaas.cluster.agent.runtime.AgentHealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
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
 * Admin endpoint — cluster-agent runtime knob 조회/변경.
 *
 * <p>Endpoint:
 * <ul>
 *   <li>{@code GET  /v1/admin/agent/heartbeat-staleness} — 현재 임계치 조회</li>
 *   <li>{@code POST /v1/admin/agent/heartbeat-staleness} — 임계치 변경 (재시작 없이)</li>
 * </ul>
 *
 * <p>임계치 변경은 본 instance 의 in-memory {@link AgentHealthService} 만 영향 — 다중 instance
 * 운영 시 모든 instance 에 호출 필요. 영구 적용은 application.yml
 * {@code cluster-agent.health.heartbeat-staleness-threshold} 변경 후 재배포.
 *
 * <p>활용 예: agent fleet 의 한 cluster 가 network jitter 로 heartbeat 가 95-110s 사이로
 * 흔들려서 false-positive unhealthy 가 자주 발생 → 운영자가 즉시 threshold 를 180s 로 늘려잡고
 * stable 화 확인 후 영구 적용 결정.
 */
@RestController
@RequestMapping("/v1/admin/agent")
@RequiredArgsConstructor
@Tag(name = "Admin (cluster-agent)", description = "운영자용 cluster-agent runtime knob")
public class AdminAgentController {

    private static final Logger log = LoggerFactory.getLogger(AdminAgentController.class);

    private final AgentHealthService agentHealthService;

    @GetMapping("/heartbeat-staleness")
    @Operation(
            summary = "Heartbeat staleness threshold 조회",
            description = "현재 instance 의 heartbeat staleness 임계치. 이 시간을 초과하면 agent unhealthy " + "로 판정. default 90s.")
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> getThreshold() {
        Duration current = agentHealthService.getHeartbeatStalenessThreshold();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("threshold", current.toString()); // (PT90S)
        body.put("thresholdSeconds", current.getSeconds());
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "heartbeat staleness threshold", body));
    }

    @PostMapping("/heartbeat-staleness")
    @Operation(
            summary = "Heartbeat staleness threshold 변경 (in-memory)",
            description = "본 instance 의 임계치 즉시 변경. 재시작 없이 적용. 다중 instance 운영 시 모든 "
                    + "instance 에 호출 필요. 영구 적용은 application.yml 변경 후 재배포.")
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> setThreshold(
            @RequestBody @NotNull ThresholdRequest req) {
        Duration parsed = parseDuration(req);
        Duration previous = agentHealthService.getHeartbeatStalenessThreshold();
        agentHealthService.setHeartbeatStalenessThreshold(parsed);
        log.warn("Admin updated agent heartbeat staleness threshold: {} → {} (caller-instance only)", previous, parsed);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("previous", previous.toString());
        body.put("current", parsed.toString());
        body.put("currentSeconds", parsed.getSeconds());
        body.put(
                "warning",
                "in-memory only — multi-instance 운영 시 모든 instance 에 동일 호출 필요. " + "영구 적용은 application.yml 변경 후 재배포.");
        return ResponseEntity.ok(
                ApiSuccessResponse.of(HttpStatus.OK.value(), "heartbeat staleness threshold updated", body));
    }

    /**
     * 요청 body 의 {@code threshold} (e.g. {@code PT2M}) 또는 {@code thresholdSeconds}
     * (long) 우선순위 ISO > seconds. 둘 다 없으면 400.
     */
    private static Duration parseDuration(ThresholdRequest req) {
        if (req.threshold() != null && !req.threshold().isBlank()) {
            try {
                return Duration.parse(req.threshold());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "Invalid threshold ISO-8601 duration (e.g. PT90S, PT2M): " + req.threshold(), e);
            }
        }
        if (req.thresholdSeconds() != null) {
            return Duration.ofSeconds(req.thresholdSeconds());
        }
        throw new IllegalArgumentException("Either 'threshold' (ISO-8601) or 'thresholdSeconds' must be set");
    }

    /**
     * Request body — {@code threshold} duration (e.g. "PT90S", "PT2M") 또는
     * {@code thresholdSeconds} long. 둘 중 하나만 필요.
     */
    public record ThresholdRequest(String threshold, Long thresholdSeconds) {}
}
