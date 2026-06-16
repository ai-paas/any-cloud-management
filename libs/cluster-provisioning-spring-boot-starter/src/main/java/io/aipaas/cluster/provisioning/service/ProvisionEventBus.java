package io.aipaas.cluster.provisioning.service;

import io.aipaas.cluster.provisioning.core.ProvisionEvent;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Pulumi engine event 의 in-process publish/subscribe 채널.
 *
 * <p>{@link io.aipaas.cluster.provisioning.service.impl.PulumiCommandServiceImpl} 가 streaming 모드로
 * 받은 raw event 를 {@link #publish(ProvisionEvent)} 로 push 하고, host 측 SSE controller / dashboard
 * / audit consumer 가 {@link #asFlux()} 로 subscribe.
 *
 * <p>backpressure 전략: {@code Sinks.many().multicast().onBackpressureBuffer(1024)} — slow consumer
 * 가 있으면 가장 오래된 event 부터 drop. provisioning event 는 정보성 (잃어도 audit 은 별도 DB 에
 * 저장) 이라 OK.
 *
 * <p>operation 별 필터링: subscriber 가 {@link Flux#filter(java.util.function.Predicate)} 로
 * {@code event.operationId().equals(target)} 비교. 본 bus 는 fan-out 만 담당하고 filter 책임은
 * subscriber 측.
 */
@Slf4j
public class ProvisionEventBus {

	private static final int BUFFER_CAPACITY = 1024;

	private final Sinks.Many<ProvisionEvent> sink = Sinks.many()
			.multicast()
			.onBackpressureBuffer(BUFFER_CAPACITY, false);

	/** Publish — slow subscriber 가 있으면 oldest 부터 drop. emit 실패는 log 만. */
	public void publish(ProvisionEvent event) {
		if (event == null) {
			return;
		}
		Sinks.EmitResult res = sink.tryEmitNext(event);
		if (res.isFailure()) {
			log.warn("ProvisionEventBus emit failed: {} — operationId={} type={}",
					res, event.operationId(), event.type());
		}
	}

	/** Subscribe — multicast 이므로 여러 subscriber 가 같은 stream 을 받는다. */
	public Flux<ProvisionEvent> asFlux() {
		return sink.asFlux();
	}
}
