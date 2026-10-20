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
 * JWT signing key 를 DB 에 보관. 메모리 보관 시 backend 재시작마다 키가 변경돼 기발급
 * registration_token 전체가 검증 실패.
 *
 * <p>부팅 시 row 부재면 64바이트 난수 생성 후 삽입. replica 동시 기동 시 양쪽이 insert 를
 * 시도하며 UNIQUE 제약으로 하나만 성공, 실패한 쪽은 {@link DataIntegrityViolationException}
 * 수신 후 재조회.
 *
 * <p>{@code active=true} 중 최신 row 사용. 키 교체 시 새 row 삽입 후 이전 row 를
 * {@code active=false} 로 변경.
 *
 * <p>이 bean 존재 시 starter 의 {@code PropertySigningKeyResolver} 미등록.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JpaSigningKeyResolver implements SigningKeyResolver {

    /** HMAC-SHA256 권장 길이: 32B (256bit) 최소, 64B (512bit) 권장 (Brute-force 내성). */
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

        // 첫 startup: 생성 + insert. UNIQUE 제약으로 race 안전.
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
            // 다른 instance 가 먼저 insert: re-read.
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
