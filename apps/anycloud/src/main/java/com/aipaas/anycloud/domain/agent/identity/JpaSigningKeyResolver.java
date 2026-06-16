package com.aipaas.anycloud.domain.agent.identity;

import com.aipaas.anycloud.domain.agent.AgentSigningKeyEntity;
import com.aipaas.anycloud.domain.agent.AgentSigningKeyRepository;
import io.aipaas.cluster.agent.identity.SigningKeyResolver;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB-backed {@link SigningKeyResolver} — JWT signing key 를 영구 저장해 backend 재시작 후에도
 * 기존 등록된 cluster-agent 의 registration_token 이 검증 가능하게 함.
 *
 * <p><b>Lifecycle</b>: starter 의 {@code JwtRegistrationTokenService.initSigningKey} 가 startup 시
 * 1회 호출. row 가 없으면 secure-random 64B 생성 + insert. 이후 모든 instance 는 동일 row 를 read.
 *
 * <p><b>Race-safety</b>: 다중 instance 가 동시에 startup 하는 경우 둘 다 insert 시도 → UNIQUE 제약
 * 으로 1건만 성공. 진 instance 는 {@link DataIntegrityViolationException} catch 후 re-read.
 *
 * <p><b>Rotation (future)</b>: 새 키 발급 시 새 row insert + 이전 row {@code active=false} 마킹.
 * 본 resolver 는 {@code active=true} 중 최근 row 자동 선택 — rotation 의 cutover 는 transaction
 * boundary 안에서 단일 atomic UPDATE+INSERT 로 처리하면 zero-downtime.
 *
 * <p><b>충돌 회피</b>: starter 의 default {@code PropertySigningKeyResolver} 는
 * {@code @ConditionalOnMissingBean} — 본 {@code @Component} 가 등록되면 starter default 가
 * skip 됨.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JpaSigningKeyResolver implements SigningKeyResolver {

    /** HMAC-SHA256 권장 길이 — 32B (256bit) 최소, 64B (512bit) 권장 (Brute-force 내성). */
    private static final int KEY_BYTES = 64;

    private final AgentSigningKeyRepository repository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public byte[] resolveSigningKey() {
        Optional<AgentSigningKeyEntity> existing = repository.findFirstByActiveTrueOrderByIdDesc();
        if (existing.isPresent()) {
            byte[] bytes = existing.get().getKeyBytes();
            log.info(
                    "Loaded persistent JWT signing key (key_id={}, length={}b)",
                    existing.get().getKeyId(),
                    bytes.length);
            return bytes;
        }

        // 첫 startup — 생성 + insert. UNIQUE 제약으로 race 안전.
        byte[] bytes = generate();
        String keyId = UUID.randomUUID().toString();
        try {
            AgentSigningKeyEntity row = AgentSigningKeyEntity.builder()
                    .keyId(keyId)
                    .keyBytes(bytes)
                    .algorithm("HS256")
                    .active(true)
                    .build();
            repository.saveAndFlush(row);
            log.warn(
                    "First-time JWT signing key generated and persisted (key_id={}, length={}b). "
                            + "이 키는 영구 저장 — 재시작 후에도 동일. Rotation 필요 시 별도 admin operation.",
                    keyId,
                    bytes.length);
            return bytes;
        } catch (DataIntegrityViolationException race) {
            // 다른 instance 가 먼저 insert — re-read.
            log.info("Race detected on first-time signing key insert (key_id={}) — re-reading persisted row", keyId);
            AgentSigningKeyEntity winner = repository
                    .findFirstByActiveTrueOrderByIdDesc()
                    .orElseThrow(() ->
                            new IllegalStateException("DataIntegrityViolation but no row found — corruption", race));
            return winner.getKeyBytes();
        }
    }

    private static byte[] generate() {
        byte[] bytes = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
