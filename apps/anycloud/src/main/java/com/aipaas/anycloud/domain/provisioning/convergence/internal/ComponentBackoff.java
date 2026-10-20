package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * 컴포넌트 재적용 간격.
 *
 * <p>상한을 둡니다. 무한히 배증하면 재시도 간격이 며칠 단위가 됩니다.
 * 워크플로의 BLOCKED 처럼 아예 멈추지도 않습니다. CSP 쿼터나 이미지 문제는 몇 시간 뒤에
 * 해결되는 경우가 있습니다.
 */
final class ComponentBackoff {

    private static final Duration BASE = Duration.ofMinutes(1);
    private static final Duration CAP = Duration.ofHours(1);
    /** 2^6 분 = 64분 > 상한. 그 이상은 계산할 필요가 없고 shift overflow 도 막는다. */
    private static final int MAX_EXPONENT = 6;

    private ComponentBackoff() {}

    static ZonedDateTime nextAttemptAt(int attempts, Clock clock) {
        int exponent = Math.min(Math.max(attempts, 1) - 1, MAX_EXPONENT);
        Duration delay = BASE.multipliedBy(1L << exponent);
        return ZonedDateTime.now(clock).plus(delay.compareTo(CAP) > 0 ? CAP : delay);
    }

    /** null 은 아직 한 번도 시도하지 않은 상태로 본다. */
    static boolean isDue(ZonedDateTime nextAttemptAt, Clock clock) {
        return nextAttemptAt == null || !nextAttemptAt.isAfter(ZonedDateTime.now(clock));
    }
}
