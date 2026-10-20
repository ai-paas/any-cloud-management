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
 * JWT signing key 를 DB 에 보관합니다. 메모리에만 두면 backend 가 재시작할 때마다 키가 바뀌어
 * 이미 발급된 registration_token 이 전부 검증에 실패합니다.
 *
 * <p>부팅 때 row 가 없으면 64바이트 난수를 만들어 넣습니다. replica 가 동시에 뜨면 둘 다 insert 를
 * 시도하는데, UNIQUE 제약으로 하나만 성공하고 진 쪽은 {@link DataIntegrityViolationException} 을
 * 받아 다시 읽습니다.
 *
 * <p>{@code active=true} 중 가장 최근 row 를 씁니다. 키를 교체할 때는 새 row 를 넣고 이전 row 를
 * {@code active=false} 로 내리면 됩니다.
 *
 * <p>이 bean 이 있으면 starter 의 {@code PropertySigningKeyResolver} 는 등록되지 않습니다.
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
