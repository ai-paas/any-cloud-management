package com.aipaas.anycloud.domain.provisioning.convergence.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * 컴포넌트 재적용 간격.
 *
 * <p>상한 적용. 무제한 배증 시 재시도 간격이 일 단위로 증가. 워크플로의 BLOCKED 와 달리 중단은
 * 하지 않음. CSP 쿼터나 이미지 문제는 수 시간 후 해소되는 경우 존재.
 */
final class ComponentBackoff {

    private static final Duration BASE = Duration.ofMinutes(1);
    private static final Duration CAP = Duration.ofHours(1);
    /** 2^6 분 = 64분 > 상한. 초과 구간은 계산 불필요하며 shift overflow 도 차단. */
    private static final int MAX_EXPONENT = 6;

    private ComponentBackoff() {}

    static ZonedDateTime nextAttemptAt(int attempts, Clock clock) {
        int exponent = Math.min(Math.max(attempts, 1) - 1, MAX_EXPONENT);
        Duration delay = BASE.multipliedBy(1L << exponent);
        return ZonedDateTime.now(clock).plus(delay.compareTo(CAP) > 0 ? CAP : delay);
    }

    /** null 은 미시도 상태로 판정. */
    static boolean isDue(ZonedDateTime nextAttemptAt, Clock clock) {
        return nextAttemptAt == null || !nextAttemptAt.isAfter(ZonedDateTime.now(clock));
    }
}
