package com.aipaas.anycloud.domain.operation.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.operation.OperationRepository;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.time.LocalDateTime;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class OperationCleanupSweeperTest extends AbstractUnitTest {

    private OperationRepository repo;
    private OperationCleanupSweeper sweeper;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(OperationRepository.class);
        sweeper = new OperationCleanupSweeper(repo);
        ReflectionTestUtils.setField(sweeper, "enabled", true);
        ReflectionTestUtils.setField(sweeper, "retentionDays", 30);
    }

    @Test
    void sweep_enabled_callsRepoWithCutoff() {
        when(repo.deleteCompletedBefore(any(LocalDateTime.class))).thenReturn(42);

        sweeper.sweep();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repo).deleteCompletedBefore(captor.capture());

        // cutoff = now - 30 days, ±1 분 안.
        LocalDateTime expected = LocalDateTime.now().minusDays(30);
        Assertions.assertThat(captor.getValue()).isBetween(expected.minusMinutes(1), expected.plusMinutes(1));
    }

    @Test
    void sweep_disabled_skipsRepo() {
        ReflectionTestUtils.setField(sweeper, "enabled", false);
        sweeper.sweep();
        verify(repo, never()).deleteCompletedBefore(any());
    }

    @Test
    void sweep_repoThrows_swallowsException() {
        when(repo.deleteCompletedBefore(any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("DB connection lost"));

        // 예외 던지지 않아야 함 — scheduler chain 보호.
        sweeper.sweep();

        verify(repo, times(1)).deleteCompletedBefore(any());
    }

    @Test
    void sweep_customRetention_isHonored() {
        ReflectionTestUtils.setField(sweeper, "retentionDays", 7);
        when(repo.deleteCompletedBefore(any(LocalDateTime.class))).thenReturn(5);

        sweeper.sweep();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repo).deleteCompletedBefore(captor.capture());

        LocalDateTime expected = LocalDateTime.now().minusDays(7);
        Assertions.assertThat(captor.getValue()).isBetween(expected.minusMinutes(1), expected.plusMinutes(1));
    }
}
