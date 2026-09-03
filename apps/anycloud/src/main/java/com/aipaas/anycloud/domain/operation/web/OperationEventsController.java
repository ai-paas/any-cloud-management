package com.aipaas.anycloud.domain.operation.web;

import com.aipaas.anycloud.common.validation.ApiValidationConstants;
import com.aipaas.anycloud.domain.operation.OperationEntity;
import com.aipaas.anycloud.domain.operation.OperationResponse;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.operation.model.OperationState;
import io.aipaas.cluster.provisioning.internal.ProvisionEventBus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/**
 * Server-Sent Events 채널 — operation 진행 상황을 push.
 * <p>
 * 단순 구현: 일정 간격으로 DB 의 operation row 를 polling 하여 변경 시 emit. 향후 메시지 버스
 * (Redis Pub/Sub / EventBus) 연동으로 즉시 push 가능. 클라이언트는 SSE 표준 자동 reconnect 사용.
 * <pre>
 *  GET /v1/operations/{id}/events           Accept: text/event-stream
 *  GET /v1/clusters/{name}/events
 * </pre>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1")
@Tag(name = "SSE Streams (v1)", description = "Operation / Cluster 이벤트 실시간 스트리밍 (SSE)")
@Validated
public class OperationEventsController {

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
    private static final Duration SSE_TIMEOUT = Duration.ofMinutes(30);

    private final OperationService operationService;
    private final ProvisionEventBus provisionEventBus;
    // 백그라운드 poll. 운영 환경에선 message bus 가 push 하는 형태로 교체 권장.
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "sse-operation-poller");
        t.setDaemon(true);
        return t;
    });

    @GetMapping(path = "/operations/{operationId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "operation 진행 이벤트 SSE",
            description = "state/progress 가 바뀔 때마다 push. terminal state 도달 시 자동 종료.")
    public SseEmitter operationEvents(
            @PathVariable @Pattern(regexp = ApiValidationConstants.OPERATION_ID_PATTERN) String operationId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT.toMillis());
        var holder = new java.util.concurrent.atomic.AtomicReference<String>(); // 이전 snapshot 비교용

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        var op = operationService.findById(operationId).orElse(null);
                        if (op == null) {
                            sendAndComplete(emitter, "not-found", null);
                            return;
                        }
                        String snapshot = snapshotKey(op);
                        if (!snapshot.equals(holder.get())) {
                            holder.set(snapshot);
                            emitter.send(SseEmitter.event()
                                    .name(
                                            op.getState().isTerminal()
                                                    ? op.getState().name().toLowerCase()
                                                    : "progress")
                                    .data(OperationResponse.from(op)));
                        }
                        if (op.getState().isTerminal()) {
                            emitter.complete();
                        }
                    } catch (IOException e) {
                        // 클라이언트 disconnect — 그냥 종료.
                        emitter.complete();
                    } catch (Exception e) {
                        log.warn("SSE operation poll error: id={}, err={}", operationId, e.toString());
                        emitter.completeWithError(e);
                    }
                },
                0,
                POLL_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);

        // Pulumi engine event 실시간 push.
        // State polling 과 별개로 ProvisionEventBus 의 raw event 도 같은 SseEmitter 로 emit (event name
        // "pulumi"). 클라이언트는 event name 으로 분기 처리 (progress / pulumi / failed / completed).
        Disposable pulumiSub = provisionEventBus
                .asFlux()
                .filter(e -> operationId.equals(e.operationId()))
                .subscribe(
                        e -> {
                            try {
                                emitter.send(SseEmitter.event().name("pulumi").data(e));
                            } catch (IOException io) {
                                // disconnect — 다음 cycle 에 onCompletion 로 정리.
                            }
                        },
                        err -> log.warn("SSE pulumi sub error: id={}, err={}", operationId, err.toString()));

        Runnable cleanup = () -> {
            task.cancel(true);
            pulumiSub.dispose();
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());
        return emitter;
    }

    @GetMapping(path = "/clusters/{clusterName}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "cluster lifecycle 이벤트 SSE",
            description = "해당 cluster 의 모든 operation 변화를 합쳐서 push. 클러스터 생성~삭제까지 단일 stream.")
    public SseEmitter clusterEvents(
            @PathVariable
                    @Pattern(regexp = ApiValidationConstants.K8S_NAME_PATTERN)
                    @Size(max = ApiValidationConstants.K8S_NAME_MAX)
                    String clusterName) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT.toMillis());
        var holder = new java.util.concurrent.atomic.AtomicReference<String>();

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        var ops = operationService.listByResource("cluster", clusterName, 5);
                        StringBuilder sb = new StringBuilder();
                        for (OperationEntity op : ops) {
                            sb.append(snapshotKey(op)).append(';');
                        }
                        String snapshot = sb.toString();
                        if (!snapshot.equals(holder.get())) {
                            holder.set(snapshot);
                            emitter.send(SseEmitter.event()
                                    .name("operations-changed")
                                    .data(ops.stream()
                                            .map(OperationResponse::from)
                                            .toList()));
                        }
                    } catch (IOException e) {
                        emitter.complete();
                    } catch (Exception e) {
                        log.warn("SSE cluster poll error: cluster={}, err={}", clusterName, e.toString());
                        emitter.completeWithError(e);
                    }
                },
                0,
                POLL_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);

        emitter.onCompletion(() -> task.cancel(true));
        emitter.onTimeout(() -> task.cancel(true));
        emitter.onError(t -> task.cancel(true));
        return emitter;
    }

    private static String snapshotKey(OperationEntity op) {
        return op.getId() + ":"
                + (op.getState() == null ? OperationState.PENDING : op.getState()) + ":"
                + (op.getCurrentStep() == null ? "" : op.getCurrentStep()) + ":"
                + (op.getPercent() == null ? -1 : op.getPercent()) + ":"
                + (op.getStepIndex() == null ? -1 : op.getStepIndex());
    }

    private static void sendAndComplete(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data == null ? "" : data));
        } catch (IOException ignore) {
        }
        emitter.complete();
    }
}
