package com.aipaas.anycloud.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveDataRedactorTest {

    @Test
    void redactsAwsAccessKeyId() {
        String redacted = SensitiveDataRedactor.redact("error: InvalidAccessKeyId AKIAIOSFODNN7EXAMPLE is not valid");
        assertThat(redacted).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(redacted).contains("<AWS_KEY:****MPLE>");
    }

    @Test
    void redactsAwsArn() {
        String redacted = SensitiveDataRedactor.redact("User: arn:aws:iam::123456789012:user/dev is not authorized");
        assertThat(redacted).doesNotContain("arn:aws:iam::123456789012:user/dev");
        assertThat(redacted).contains("<AWS_ARN:");
    }

    @Test
    void redactsAwsAccountId() {
        String redacted = SensitiveDataRedactor.redact("account 123456789012 has insufficient permissions");
        assertThat(redacted).doesNotContain("123456789012");
        assertThat(redacted).contains("<AWS_ACCT:****9012>");
    }

    @Test
    void redactsAzureSubscriptionUuid() {
        String redacted = SensitiveDataRedactor.redact("subscription 12345678-1234-1234-1234-1234567890ab failed auth");
        assertThat(redacted).doesNotContain("12345678-1234-1234-1234-1234567890ab");
        assertThat(redacted).contains("<UUID:****90ab>");
    }

    @Test
    void redactsOciFingerprint() {
        String redacted = SensitiveDataRedactor.redact(
                "fingerprint 12:34:56:78:90:ab:cd:ef:12:34:56:78:90:ab:cd:ef does not match");
        assertThat(redacted).doesNotContain("12:34:56:78:90:ab:cd:ef:12:34:56:78:90:ab:cd:ef");
        assertThat(redacted).contains("<OCI_FP:");
    }

    @Test
    void redactsOcid() {
        String redacted = SensitiveDataRedactor.redact("ocid1.user.oc1..aaaaaaaaexampleocidvalue failed lookup");
        assertThat(redacted).doesNotContain("ocid1.user.oc1..aaaaaaaaexampleocidvalue");
        assertThat(redacted).contains("<OCID:");
    }

    @Test
    void redactsPrivateKeyBlock() {
        String redacted = SensitiveDataRedactor.redact(
                "key: -----BEGIN RSA PRIVATE KEY-----\nMIIBOgIBAAJBAJ\n-----END RSA PRIVATE KEY----- end");
        assertThat(redacted).doesNotContain("MIIBOgIBAAJBAJ");
        assertThat(redacted).contains("<PRIVATE_KEY:");
    }

    @Test
    void nullInputReturnsNull() {
        assertThat(SensitiveDataRedactor.redact(null)).isNull();
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertThat(SensitiveDataRedactor.redact("")).isEmpty();
    }

    @Test
    void cleanInputUnchanged() {
        String input = "Cluster demo-aws-01 provisioning started";
        assertThat(SensitiveDataRedactor.redact(input)).isEqualTo(input);
    }

    @Test
    void idempotentOnAlreadyRedactedString() {
        String once = SensitiveDataRedactor.redact("AKIAIOSFODNN7EXAMPLE");
        String twice = SensitiveDataRedactor.redact(once);
        assertThat(twice).isEqualTo(once);
    }
}
