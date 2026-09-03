package com.aipaas.anycloud.domain.agent.bootstrap;

import com.aipaas.anycloud.domain.agent.BootstrapJtiEntity;
import com.aipaas.anycloud.domain.agent.BootstrapJtiRepository;
import io.aipaas.cluster.agent.core.IdempotencyStore;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * IdempotencyStore SPI 의 RDB (MariaDB) 기반 구현. Redis 대체.
 *
 * <p>동작: INSERT IGNORE 등가 — primary key (jti) 충돌 시 DataIntegrityViolationException 발생,
 * caller 가 false 반환. 첫 호출만 성공, 이후 호출은 모두 false.
 *
 * <p>TTL 처리: {@link com.aipaas.anycloud.domain.agent.auth.BootstrapJtiCleanupSweeper} 가 매일 만료된
 * 행 삭제. 만료된 jti 가 재사용될 가능성은 0 (JWT 자체의 서명+exp 검증으로 차단).
 *
 * <p>Propagation REQUIRES_NEW — caller 의 transaction context 와 분리해서 jti INSERT 가 다른 작업
 * 결과에 영향 안 받게 처리. JWT verify 실패 같은 rollback 시점에도 jti 는 안전하게 lock 됨.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JpaIdempotencyStore implements IdempotencyStore {

    private final BootstrapJtiRepository repository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryLock(String key, Duration ttl) {
        // 본 SPI 는 key 가 raw jti — JwtRegistrationTokenService 가 "bootstrap:jti:" prefix 붙여서
        // 전달함. PK 길이 64 제약 (V12 schema) 안에 들어가도록 prefix 그대로 사용.
        LocalDateTime expiresAt = LocalDateTime.now().plus(ttl);
        BootstrapJtiEntity entity =
                BootstrapJtiEntity.builder().jti(key).expiresAt(expiresAt).build();
        try {
            repository.saveAndFlush(entity);
            return true;
        } catch (DataIntegrityViolationException e) {
            // PK 중복 = 이미 사용됨. JWT replay 차단.
            log.debug("idempotency: jti already used key={}", key);
            return false;
        }
    }
}
