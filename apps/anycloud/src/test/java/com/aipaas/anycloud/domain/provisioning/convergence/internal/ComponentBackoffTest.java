package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class ComponentBackoffTest {

    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private long minutesFromNow(int attempts) {
        return Duration.between(NOW, ComponentBackoff.nextAttemptAt(attempts, clock).toInstant())
                .toMinutes();
    }

    @Test
    void firstAttemptWaitsOneMinute() {
        assertThat(minutesFromNow(1)).isEqualTo(1);
    }

    @Test
    void backoffDoublesPerAttempt() {
        assertThat(minutesFromNow(2)).isEqualTo(2);
        assertThat(minutesFromNow(3)).isEqualTo(4);
        assertThat(minutesFromNow(4)).isEqualTo(8);
        assertThat(minutesFromNow(5)).isEqualTo(16);
        assertThat(minutesFromNow(6)).isEqualTo(32);
    }

    @Test
    void backoffCapsAtOneHour() {
        // 상한이 없으면 며칠 뒤 재시도가 되어 사실상 정지와 같아진다.
        assertThat(minutesFromNow(7)).isEqualTo(60);
        assertThat(minutesFromNow(20)).isEqualTo(60);
        assertThat(minutesFromNow(1000)).isEqualTo(60);
    }

    @Test
    void zeroOrNegativeAttemptsTreatedAsFirst() {
        assertThat(minutesFromNow(0)).isEqualTo(1);
        assertThat(minutesFromNow(-3)).isEqualTo(1);
    }

    @Test
    void isDue_trueWhenNextAttemptIsNullOrPast() {
        // null 은 아직 한 번도 시도하지 않은 상태다.
        assertThat(ComponentBackoff.isDue(null, clock)).isTrue();
        assertThat(ComponentBackoff.isDue(ZonedDateTime.now(clock).minusSeconds(1), clock))
                .isTrue();
    }

    @Test
    void isDue_falseWhenNextAttemptIsFuture() {
        assertThat(ComponentBackoff.isDue(ZonedDateTime.now(clock).plusMinutes(5), clock))
                .isFalse();
    }
}
