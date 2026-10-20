package com.aipaas.anycloud.domain.provisioning.bootstrap;

import java.time.Duration;

/**
 * 부트스트랩 단계의 재시도 예산.
 *
 * <p>생성 직후 노드는 SSH 를 즉시 수용하지 않음. 7개 동시 생성 시 한 노드의 부팅 지연 상황에서
 * 재시도 3회, 고정 10초 설정은 20초 만에 중단. cloud-init 타임아웃 20분을 소진하기 전에
 * 클러스터 전체 실패.
 *
 * <p>간격은 증가시키되 상한 설정. 상한 부재 시 지수 증가가 단계 예산 전체를 소진.
 */
public final class BootstrapRetryPolicy {

    /** 노드가 SSH 를 받을 때까지. 동시에 여럿 띄우면 부팅이 더 늦다. */
    public static final int PREPARATION_ATTEMPTS = 10;

    /** 노드 준비 완료 이후라 장시간 대기 불필요. 실패 조기 검출 우선. */
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
