package com.aipaas.anycloud.domain.provisioning.bootstrap;

import java.time.Duration;

/**
 * 부트스트랩 단계의 재시도 예산.
 *
 * <p>갓 만들어진 노드는 바로 SSH 를 받지 않는다. 7개를 동시에 띄웠을 때 한 노드가 늦게 부팅됐는데
 * 재시도 3회에 고정 10초라 20초 만에 포기했다 — cloud-init 타임아웃 20분을 써보지도 못하고
 * 클러스터가 통째로 실패했다.
 *
 * <p>간격은 늘리되 상한을 둔다. 상한이 없으면 지수 증가가 단계 예산을 통째로 먹는다.
 */
public final class BootstrapRetryPolicy {

    /** 노드가 SSH 를 받을 때까지. 동시에 여럿 띄우면 부팅이 더 늦다. */
    public static final int PREPARATION_ATTEMPTS = 10;

    /** 노드가 이미 준비된 뒤라 길게 기다릴 이유가 없다. 실패는 빨리 알아야 한다. */
    public static final int MASTER_INIT_ATTEMPTS = 2;

    private static final Duration BASE_DELAY = Duration.ofSeconds(10);
    private static final Duration MAX_DELAY = Duration.ofSeconds(30);

    private BootstrapRetryPolicy() {}

    /**
     * 다음 시도까지 쉴 시간.
     *
     * @param attempt 방금 실패한 시도 번호 (1부터)
     */
    public static Duration delayBeforeRetry(int attempt) {
        Duration grown = BASE_DELAY.multipliedBy(Math.max(1, attempt));
        return grown.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : grown;
    }
}
