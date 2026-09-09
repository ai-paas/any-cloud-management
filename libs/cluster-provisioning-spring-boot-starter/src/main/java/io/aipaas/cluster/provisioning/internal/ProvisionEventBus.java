package io.aipaas.cluster.provisioning.internal;

import io.aipaas.cluster.provisioning.api.ProvisionEvent;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** Pulumi engine event 의 in-process publish/subscribe 채널. */
@Slf4j
public class ProvisionEventBus {

    private static final int BUFFER_CAPACITY = 1024;

    private final Sinks.Many<ProvisionEvent> sink =
            Sinks.many().multicast().onBackpressureBuffer(BUFFER_CAPACITY, false);

    /** Publish — slow subscriber 가 있으면 oldest 부터 drop. emit 실패는 log 만. */
    public void publish(ProvisionEvent event) {
        if (event == null) {
            return;
        }
        Sinks.EmitResult res = sink.tryEmitNext(event);
        if (res.isFailure()) {
            log.warn(
                    "ProvisionEventBus emit failed: {} — operationId={} type={}",
                    res,
                    event.operationId(),
                    event.type());
        }
    }

    /** Subscribe — multicast 이므로 여러 subscriber 가 같은 stream 을 받는다. */
    public Flux<ProvisionEvent> asFlux() {
        return sink.asFlux();
    }
}
