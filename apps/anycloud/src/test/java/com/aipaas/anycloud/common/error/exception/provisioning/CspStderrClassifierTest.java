package com.aipaas.anycloud.common.error.exception.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CspStderrClassifierTest {

    @Test
    void awsInvalidAccessKeyMapsToPermanent() {
        ProvisioningException e = CspStderrClassifier.classifyPulumi(
                "up", "error: InvalidAccessKeyId: The AWS Access Key Id you provided does not exist");
        assertThat(e).isInstanceOf(PermanentProvisioningFailure.class);
        assertThat(e.isTransient()).isFalse();
    }

    @Test
    void awsThrottlingMapsToTransient() {
        ProvisioningException e = CspStderrClassifier.classifyPulumi("up", "error: ThrottlingException: Rate exceeded");
        assertThat(e).isInstanceOf(TransientProvisioningFailure.class);
        assertThat(e.isTransient()).isTrue();
    }

    @Test
    void gcpPermissionDeniedMapsToPermanent() {
        ProvisioningException e =
                CspStderrClassifier.classifyPulumi("preview", "googleapi: Error 403: ... PERMISSION_DENIED");
        assertThat(e).isInstanceOf(PermanentProvisioningFailure.class);
    }

    @Test
    void azureForbiddenMapsToPermanent() {
        ProvisioningException e =
                CspStderrClassifier.classifyPulumi("up", "AuthorizationFailed: The client does not have authorization");
        assertThat(e).isInstanceOf(PermanentProvisioningFailure.class);
    }

    @Test
    void unknownStderrDefaultsToPulumiExecution() {
        ProvisioningException e =
                CspStderrClassifier.classifyPulumi("up", "some unexpected error nobody has seen before");
        assertThat(e).isInstanceOf(PulumiExecutionException.class);
        assertThat(e.isTransient()).isTrue();
    }

    @Test
    void nullStderrDefaultsToPulumiExecution() {
        ProvisioningException e = CspStderrClassifier.classifyPulumi("up", null);
        assertThat(e).isInstanceOf(PulumiExecutionException.class);
    }

    @Test
    void classifyGenericPermanentReturnsPermanentFailure() {
        ProvisioningException e = CspStderrClassifier.classify("VM options check", "InvalidAccessKeyId");
        assertThat(e).isInstanceOf(PermanentProvisioningFailure.class);
    }

    @Test
    void classifyGenericServiceUnavailableReturnsTransient() {
        ProvisioningException e = CspStderrClassifier.classify("region listing", "ServiceUnavailable: try again later");
        assertThat(e).isInstanceOf(TransientProvisioningFailure.class);
    }

    @Test
    void caseInsensitiveTokenMatching() {
        ProvisioningException upper = CspStderrClassifier.classifyPulumi("up", "PERMISSION_DENIED");
        ProvisioningException lower = CspStderrClassifier.classifyPulumi("up", "permission_denied");
        assertThat(upper).isInstanceOf(PermanentProvisioningFailure.class);
        assertThat(lower).isInstanceOf(PermanentProvisioningFailure.class);
    }
}
