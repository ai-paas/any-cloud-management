package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * 컴포넌트 재적용 간격.
 *
 * <p>상한이 있는 이유 — 무한 배증은 며칠 뒤 재시도가 되어 사실상 정지와 같다. 반대로 워크플로우의
 * BLOCKED 처럼 완전히 멈추지 않는 이유는, CSP 쿼터나 이미지 문제로 몇 시간 뒤 성공하는 경우가
 * 있어서다.
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
