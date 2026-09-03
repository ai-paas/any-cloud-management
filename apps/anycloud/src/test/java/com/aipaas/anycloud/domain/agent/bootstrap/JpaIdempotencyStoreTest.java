package com.aipaas.anycloud.domain.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.agent.BootstrapJtiEntity;
import com.aipaas.anycloud.domain.agent.BootstrapJtiRepository;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

/** Redis SET NX 의 DB 기반 대체 — INSERT IGNORE 패턴 회귀. */
class JpaIdempotencyStoreTest extends AbstractUnitTest {

    private BootstrapJtiRepository repo;
    private JpaIdempotencyStore store;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(BootstrapJtiRepository.class);
        store = new JpaIdempotencyStore(repo);
    }

    @Test
    void tryLock_firstUse_returnsTrue() {
        when(repo.saveAndFlush(any(BootstrapJtiEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean acquired = store.tryLock("bootstrap:jti:abc-123", Duration.ofMinutes(10));

        assertThat(acquired).isTrue();
    }

    @Test
    void tryLock_duplicate_returnsFalse() {
        // PK 중복 → DataIntegrityViolationException → false.
        when(repo.saveAndFlush(any(BootstrapJtiEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        boolean acquired = store.tryLock("bootstrap:jti:abc-123", Duration.ofMinutes(10));

        assertThat(acquired).isFalse();
    }

    @Test
    void tryLock_persistsKeyAndExpiresAt() {
        org.mockito.ArgumentCaptor<BootstrapJtiEntity> captor =
                org.mockito.ArgumentCaptor.forClass(BootstrapJtiEntity.class);
        when(repo.saveAndFlush(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        store.tryLock("bootstrap:jti:xyz", Duration.ofMinutes(10));

        BootstrapJtiEntity saved = captor.getValue();
        assertThat(saved.getJti()).isEqualTo("bootstrap:jti:xyz");
        // expiresAt ≈ now + 10min, ±1 분 안.
        assertThat(saved.getExpiresAt()).isAfter(java.time.LocalDateTime.now().plusMinutes(9));
        assertThat(saved.getExpiresAt()).isBefore(java.time.LocalDateTime.now().plusMinutes(11));
    }
}
