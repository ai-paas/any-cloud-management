package io.aipaas.cluster.provisioning.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.aipaas.cluster.provisioning.core.ProvisionEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * ProvisionEventBus 회귀 방지.
 *
 * <p>publish/subscribe + multicast + null safety 검증. backpressure 시나리오는 별 sprint.
 */
class ProvisionEventBusTest {

	@Test
	void publish_singleSubscriber_receivesEvent() {
		ProvisionEventBus bus = new ProvisionEventBus();
		AtomicInteger count = new AtomicInteger(0);

		bus.asFlux().subscribe(e -> count.incrementAndGet());

		bus.publish(new ProvisionEvent("op-1", "diagnostic", Instant.now(),
				"urn:test", "msg", "info", Map.of()));

		// Reactor multicast 가 즉시 emit — async timing OK without sleep.
		assertThat(count.get()).isEqualTo(1);
	}

	@Test
	void publish_multipleSubscribers_allReceive() {
		ProvisionEventBus bus = new ProvisionEventBus();
		AtomicInteger s1 = new AtomicInteger();
		AtomicInteger s2 = new AtomicInteger();

		bus.asFlux().subscribe(e -> s1.incrementAndGet());
		bus.asFlux().subscribe(e -> s2.incrementAndGet());

		bus.publish(new ProvisionEvent("op-2", "summary", Instant.now(), null, null, null, Map.of()));

		// multicast 이므로 모든 subscriber 가 같은 event 수신.
		assertThat(s1.get()).isEqualTo(1);
		assertThat(s2.get()).isEqualTo(1);
	}

	@Test
	void publish_nullEvent_silentlyIgnored() {
		ProvisionEventBus bus = new ProvisionEventBus();
		AtomicInteger count = new AtomicInteger();
		bus.asFlux().subscribe(e -> count.incrementAndGet());

		bus.publish(null);   // 본 case 가 throw 하지 않고 silent skip.

		assertThat(count.get()).isZero();
	}

	@Test
	void asFlux_lateSubscriber_receivesBufferedEvents() {
		// multicast.onBackpressureBuffer(1024) 는 subscriber 부재 시 buffer 에 event 쌓아둠.
		// 늦게 subscribe 한 subscriber 는 buffer 안 events 도 모두 받음 (catch-up replay 효과).
		ProvisionEventBus bus = new ProvisionEventBus();
		bus.publish(new ProvisionEvent("op-pre", "info", Instant.now(), null, null, null, Map.of()));

		AtomicInteger late = new AtomicInteger();
		bus.asFlux().subscribe(e -> late.incrementAndGet());

		bus.publish(new ProvisionEvent("op-post", "info", Instant.now(), null, null, null, Map.of()));

		// buffer 에 op-pre 가 보존되어 있어 late subscriber 도 수신 — 총 2개.
		assertThat(late.get()).isEqualTo(2);
	}

	@Test
	void subscriber_filterByOperationId_isolatesEventStream() {
		// Bus 자체는 fan-out — operation 별 격리는 subscriber 책임 (filter).
		ProvisionEventBus bus = new ProvisionEventBus();
		AtomicInteger op1Count = new AtomicInteger();
		AtomicInteger op2Count = new AtomicInteger();

		bus.asFlux().filter(e -> "op-1".equals(e.operationId())).subscribe(e -> op1Count.incrementAndGet());
		bus.asFlux().filter(e -> "op-2".equals(e.operationId())).subscribe(e -> op2Count.incrementAndGet());

		bus.publish(new ProvisionEvent("op-1", "x", Instant.now(), null, null, null, Map.of()));
		bus.publish(new ProvisionEvent("op-2", "x", Instant.now(), null, null, null, Map.of()));
		bus.publish(new ProvisionEvent("op-2", "x", Instant.now(), null, null, null, Map.of()));

		assertThat(op1Count.get()).isEqualTo(1);
		assertThat(op2Count.get()).isEqualTo(2);
	}
}
