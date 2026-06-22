package io.aipaas.cluster.provisioning.internal;

import com.pulumi.automation.events.DiagnosticEvent;
import com.pulumi.automation.events.EngineEvent;
import com.pulumi.automation.events.SummaryEvent;
import io.aipaas.cluster.provisioning.api.ProvisionEvent;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pulumi Automation API 의 {@link EngineEvent} 를 starter 표준 {@link ProvisionEvent} 로 변환 후
 * {@link ProvisionEventBus} 로 publish. type/message/severity 추출 + bus 실패 swallow 책임.
 *
 * <p>provisioning lifecycle 안에서 onEvent callback 으로 호출 — publish 실패가 main flow 차단하지 않도록
 * 모든 exception swallow.
 */
@Slf4j
@RequiredArgsConstructor
public class EngineEventAdapter {

    private final ProvisionEventBus eventBus;

    public void publish(String operationId, EngineEvent event) {
        try {
            ProvisionEvent provisionEvent = new ProvisionEvent(
                    operationId, inferType(event), Instant.now(), null,
                    extractMessage(event), extractSeverity(event), Map.of());
            eventBus.publish(provisionEvent);
        } catch (Exception e) {
            log.debug("Failed to publish engine event: {}", e.getMessage());
        }
    }

    private static String inferType(EngineEvent event) {
        if (event.diagnosticEvent() != null) return "diagnostic";
        if (event.summaryEvent() != null) return "summary";
        if (event.resourceOutputsEvent() != null) return "resOutputs";
        if (event.resourcePreEvent() != null) return "resourcePre";
        if (event.resourceOperationFailedEvent() != null) return "resourceFailed";
        if (event.preludeEvent() != null) return "prelude";
        if (event.standardOutputEvent() != null) return "stdout";
        if (event.policyEvent() != null) return "policy";
        if (event.cancelEvent() != null) return "cancel";
        return "unknown";
    }

    private static String extractMessage(EngineEvent event) {
        DiagnosticEvent diag = event.diagnosticEvent();
        if (diag != null) return diag.message();
        SummaryEvent summary = event.summaryEvent();
        if (summary != null) return "Pulumi update summary";
        return null;
    }

    private static String extractSeverity(EngineEvent event) {
        DiagnosticEvent diag = event.diagnosticEvent();
        return diag != null ? diag.severity() : null;
    }
}
