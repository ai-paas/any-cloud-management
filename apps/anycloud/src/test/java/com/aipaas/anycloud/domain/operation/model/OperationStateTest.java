package com.aipaas.anycloud.domain.operation.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

class OperationStateTest extends AbstractUnitTest {

    @Test
    void isTerminal_onlyForSucceededFailedCancelled() {
        assertThat(OperationState.PENDING.isTerminal()).isFalse();
        assertThat(OperationState.RUNNING.isTerminal()).isFalse();
        assertThat(OperationState.SUCCEEDED.isTerminal()).isTrue();
        assertThat(OperationState.FAILED.isTerminal()).isTrue();
        assertThat(OperationState.CANCELLED.isTerminal()).isTrue();
    }
}
