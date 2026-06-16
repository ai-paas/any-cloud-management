package com.aipaas.anycloud.common.error.exception.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import org.junit.jupiter.api.Test;

class ProvisioningExceptionHierarchyTest {

    @Test
    void transientFailureIsTransient() {
        ProvisioningException e = new TransientProvisioningFailure("upstream timeout");
        assertThat(e.isTransient()).isTrue();
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UPSTREAM_FAILED);
    }

    @Test
    void transientFailurePreservesCause() {
        Throwable cause = new RuntimeException("network blip");
        ProvisioningException e = new TransientProvisioningFailure("upstream timeout", cause);
        assertThat(e.getCause()).isSameAs(cause);
    }

    @Test
    void permanentFailureCarriesProvidedErrorCode() {
        ProvisioningException e =
                new PermanentProvisioningFailure("credential mismatch", ErrorCode.INVALID_INPUT_VALUE);
        assertThat(e.isTransient()).isFalse();
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void stateConflictMapsToStateConflictCode() {
        ProvisioningException e = new StateConflictException("only READY clusters can scale");
        assertThat(e.isTransient()).isFalse();
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STATE_CONFLICT);
    }

    @Test
    void pulumiExecutionIsTransientByDefault() {
        ProvisioningException e = new PulumiExecutionException("pulumi up exit 1");
        assertThat(e.isTransient()).isTrue();
        assertThat(e).isInstanceOf(TransientProvisioningFailure.class);
    }

    @Test
    void allSubtypesExtendCustomException() {
        assertThat(new TransientProvisioningFailure("x")).isInstanceOf(CustomException.class);
        assertThat(new PermanentProvisioningFailure("x", ErrorCode.RUNTIME_EXCEPTION))
                .isInstanceOf(CustomException.class);
        assertThat(new StateConflictException("x")).isInstanceOf(CustomException.class);
        assertThat(new PulumiExecutionException("x")).isInstanceOf(CustomException.class);
    }
}
