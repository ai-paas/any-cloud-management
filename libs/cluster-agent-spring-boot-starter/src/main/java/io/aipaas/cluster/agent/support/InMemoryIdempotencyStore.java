package io.aipaas.cluster.agent.support;

import io.aipaas.cluster.agent.core.IdempotencyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;

/**
 * In-memory {@link IdempotencyStore} — Spring Boot starter 의 zero-config default.
 *
 * <p>JWT jti (registration_token 의 1회 사용 강제) 추적. 단순 ConcurrentHashMap + lazy expire —
 * tryLock 호출 시 expired entry 정리. ScheduledExecutor 없이 piggyback cleanup 으로 충분 (TTL 짧음).
 *
 * <p><b>용도</b>: dev / PoC / single-instance demo. JWT TTL 이 짧고 (10분 default) traffic 도
 * 적은 환경에선 충분.
 *
 * <p><b>제한</b>:
 * <ul>
 *   <li>Replication 불가 — 단일 JVM only. multi-instance HA backend 면 attacker 가 다른 instance
 *       로 같은 token replay 가능. production multi-instance 는 DB-backed (e.g., MariaDB
 *       INSERT IGNORE) 권장.</li>
 *   <li>Restart 시 jti 데이터 소실 — JWT TTL 안의 token 들이 다시 사용 가능. 10분 TTL 이므로
 *       restart 직후 10분 window 만 위험. dev 환경 정도면 무시 가능.</li>
 * </ul>
 *
 * <p><b>Production 권장</b>: DB-backed (e.g., {@code bootstrap_jti_used} 테이블 + INSERT IGNORE).
 * 호스트가 자체 {@link IdempotencyStore} bean 을 등록하면 본 default 는 자동 비활성.
 */
@Slf4j
public class InMemoryIdempotencyStore implements IdempotencyStore {

	/** key (JWT jti) → expiresAt. 만료된 entry 는 lazy cleanup. */
	private final ConcurrentMap<String, Instant> entries = new ConcurrentHashMap<>();

	public InMemoryIdempotencyStore() {
		log.info("InMemoryIdempotencyStore active — dev/PoC default. "
				+ "Register your own IdempotencyStore @Bean for production (DB-backed).");
	}

	@Override
	public boolean tryLock(String key, Duration ttl) {
		if (key == null || ttl == null) {
			return false;
		}
		Instant now = Instant.now();
		Instant newExpiry = now.plus(ttl);

		// putIfAbsent — atomic. 이미 있고 not expired 면 false, 없거나 expired 면 새로 set.
		Instant existing = entries.putIfAbsent(key, newExpiry);
		if (existing == null) {
			maybeCleanup(now);
			return true;
		}
		if (existing.isBefore(now)) {
			// Expired — replace atomically.
			if (entries.replace(key, existing, newExpiry)) {
				return true;
			}
			// race — concurrent caller succeeded. treat as already locked.
			return false;
		}
		return false;
	}

	/** Lazy cleanup — 10번에 1번만 (CAS 부담 회피). 단순 random hash sampling. */
	private void maybeCleanup(Instant now) {
		// 단순 heuristic — 100 ms 단위로 한 번씩 정도.
		if ((now.toEpochMilli() & 0x7F) != 0) {
			return;
		}
		entries.entrySet().removeIf(e -> e.getValue().isBefore(now));
	}
}
