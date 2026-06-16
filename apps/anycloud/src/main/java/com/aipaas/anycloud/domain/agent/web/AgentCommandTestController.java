package com.aipaas.anycloud.domain.agent.web;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.common.web.ApiSuccessResponse;
import io.aipaas.cluster.agent.runtime.AgentCommandRouter;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry.NoActiveSessionException;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry.SessionClosedException;
import io.aipaas.cluster.agent.v1.CommandResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * agent 에 직접 명령을 보내는 테스트 endpoint.
 *
 * <p>운영 흐름에서는 KubeServiceImpl 등 기존 service 가 agent routing 으로 통합될 예정 .
 * 본 controller 는 그때까지 stream 동작 검증용.
 *
 * <p>응답: agent 가 보낸 CommandResponse 의 result Struct 를 JSON 으로 직접 노출.
 */
@Slf4j
@RestController
@RequestMapping("/v1/clusters/{clusterId}/agent")
@RequiredArgsConstructor
@Validated
@Tag(name = "Cluster Agent (v1) — runtime test", description = "Phase 3 stream 검증용 — 운영 흐름 X")
public class AgentCommandTestController {

    private final AgentCommandRouter commandRouter;

    @GetMapping("/pods")
    @Operation(
            summary = "[TEST] Agent 통해 pod list",
            description = "AgentRuntime stream 위에서 LIST_PODS 명령. 운영용 X — fabric8 direct 와 동등.")
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> listPods(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterId,
            @Parameter(description = "namespace (비우면 all)") @RequestParam(name = "namespace", required = false)
                    String namespace,
            @Parameter(description = "응답 대기 timeout (1..60초)")
                    @RequestParam(name = "timeoutSeconds", required = false, defaultValue = "10")
                    @Min(1)
                    @Max(60)
                    int timeoutSeconds) {
        CommandResponse resp = await(commandRouter.listPods(clusterId, namespace), timeoutSeconds);
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "agent response", buildResultMap(resp)));
    }

    @GetMapping("/logs")
    @Operation(
            summary = "[TEST] Agent 통해 pod log snapshot",
            description = "GET_LOG 명령. T2 의 fabric8 direct 와 동등하지만 agent 우회.")
    public ResponseEntity<ApiSuccessResponse<Map<String, Object>>> getLog(
            @PathVariable
                    @NotBlank
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterId,
            @RequestParam("namespace") @NotBlank String namespace,
            @RequestParam("pod") @NotBlank String pod,
            @RequestParam(name = "container", required = false) String container,
            @RequestParam(name = "tailLines", required = false, defaultValue = "100") @Min(1) @Max(10000) int tailLines,
            @RequestParam(name = "previous", required = false, defaultValue = "false") boolean previous,
            @RequestParam(name = "sinceSeconds", required = false, defaultValue = "0") @Min(0) @Max(86400)
                    int sinceSeconds,
            @RequestParam(name = "timeoutSeconds", required = false, defaultValue = "30") @Min(1) @Max(120)
                    int timeoutSeconds) {
        CommandResponse resp = await(
                commandRouter.getLog(clusterId, namespace, pod, container, tailLines, previous, sinceSeconds),
                timeoutSeconds);
        return ResponseEntity.ok(ApiSuccessResponse.of(HttpStatus.OK.value(), "agent response", buildResultMap(resp)));
    }

    private CommandResponse await(java.util.concurrent.CompletableFuture<CommandResponse> future, int timeoutSeconds) {
        try {
            return future.get(timeoutSeconds + 5L, TimeUnit.SECONDS); // controller-side cushion.
        } catch (TimeoutException e) {
            throw new CustomException(
                    "Agent command timed out after " + timeoutSeconds + "s", ErrorCode.CLUSTER_CONNECTION_FAILED);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof NoActiveSessionException) {
                throw new CustomException(
                        "No active agent session: " + cause.getMessage(), ErrorCode.CLUSTER_CONNECTION_FAILED);
            }
            if (cause instanceof SessionClosedException) {
                throw new CustomException(
                        "Agent stream closed mid-flight: " + cause.getMessage(), ErrorCode.CLUSTER_CONNECTION_FAILED);
            }
            throw new CustomException("Agent command failed: " + cause.getMessage(), ErrorCode.INTERNAL_SERVER_ERROR);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomException("Interrupted awaiting agent response", ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private static Map<String, Object> buildResultMap(CommandResponse resp) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", resp.getStatus().name());
        map.put("error_code", resp.getErrorCode());
        map.put("error_message", resp.getErrorMessage());
        // result Struct → Map<String, String> 단순 변환. CommandType 별로 다른 형태라 generic.
        Map<String, Object> result = new LinkedHashMap<>();
        resp.getResult().getFieldsMap().forEach((k, v) -> {
            switch (v.getKindCase()) {
                case STRING_VALUE -> result.put(k, v.getStringValue());
                case NUMBER_VALUE -> result.put(k, v.getNumberValue());
                case BOOL_VALUE -> result.put(k, v.getBoolValue());
                default -> result.put(k, v.toString());
            }
        });
        map.put("result", result);
        return map;
    }
}
