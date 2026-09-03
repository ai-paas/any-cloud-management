package io.aipaas.cluster.agent.core;

import java.time.Duration;

/**
 * JWT registration token 의 jti (1회 사용 강제) 등 멱등성 키 저장소 SPI.
 *
 * <p>Anycloud 는 MariaDB `bootstrap_jti_used` 테이블 INSERT IGNORE 패턴으로 구현 + 일배치 만료 정리.
 * 다른 프로젝트는 in-memory ConcurrentHashMap + ScheduledExecutor 로 expire 처리해도 무방. 필요 시
 * starter 가 in-memory default 구현 제공.
 *
 * <p><b>의미론</b>: 같은 key 를 두 번째로 lock 시도하면 false 를 반환해야 함 — 즉 토큰 재사용 차단.
 */
public interface IdempotencyStore {

	/**
	 * Key 가 unused 면 lock 획득 + true 반환. 이미 lock 됐으면 false.
	 *
	 * @param key  멱등성 key (예: JWT jti claim).
	 * @param ttl  최소 lock 유지 시간. 이 시간 이후 자동 expire 권장 (DB 부하 방지).
	 * @return  새로 lock 획득 성공 여부.
	 */
	boolean tryLock(String key, Duration ttl);
}
