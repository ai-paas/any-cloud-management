package com.aipaas.anycloud.domain.addon;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.addon.model.AddonState;
import org.junit.jupiter.api.Test;

/** N-13 — AddonState helper 메서드 (isRetryable, isInFlight). */
class AddonStateTest {

    @Test
    void isRetryable_onlyFailed() {
        assertThat(AddonState.FAILED.isRetryable()).isTrue();
        assertThat(AddonState.PENDING.isRetryable()).isFalse();
        assertThat(AddonState.ENQUEUED.isRetryable()).isFalse();
        assertThat(AddonState.INSTALLING.isRetryable()).isFalse();
        assertThat(AddonState.SUCCEEDED.isRetryable()).isFalse();
        assertThat(AddonState.DELETING.isRetryable()).isFalse();
        assertThat(AddonState.DELETED.isRetryable()).isFalse();
    }

    @Test
    void isInFlight_enqueuedInstallingDeleting() {
        assertThat(AddonState.ENQUEUED.isInFlight()).isTrue();
        assertThat(AddonState.INSTALLING.isInFlight()).isTrue();
        assertThat(AddonState.DELETING.isInFlight()).isTrue();

        assertThat(AddonState.PENDING.isInFlight()).isFalse();
        assertThat(AddonState.SUCCEEDED.isInFlight()).isFalse();
        assertThat(AddonState.FAILED.isInFlight()).isFalse();
        assertThat(AddonState.DELETED.isInFlight()).isFalse();
    }
}
