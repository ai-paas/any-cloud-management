package com.aipaas.anycloud.domain.provisioning.bootstrap.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * T1 (#12) — bootstrapLog sensitive content masking 회귀 보호.
 *
 * <p>Bootstrap log 수집 명령 (cloud-init-output, kubelet journal 등) 의 output 에 우연히
 * 포함될 수 있는 PEM block / kubeadm token 마스킹.
 */
class VmClusterBootstrapLogServiceMaskingTest extends AbstractUnitTest {

    @Test
    void mask_pemBlock_redactedFully() {
        String log = "kubectl output\n"
                + "-----BEGIN RSA PRIVATE KEY-----\n"
                + "MIIEpAIBAAKCAQEA1234abcd...\n"
                + "-----END RSA PRIVATE KEY-----\n"
                + "rest of log";

        String masked = VmClusterBootstrapLogServiceImpl.maskSensitive(log);

        assertThat(masked).contains("-----REDACTED-PEM-BLOCK-----");
        assertThat(masked).doesNotContain("MIIEpAIBAAKCAQEA");
        assertThat(masked).contains("kubectl output");
        assertThat(masked).contains("rest of log");
    }

    @Test
    void mask_certificateBlock_redacted() {
        String log = "before\n-----BEGIN CERTIFICATE-----\nMIID...\n-----END CERTIFICATE-----\nafter";

        String masked = VmClusterBootstrapLogServiceImpl.maskSensitive(log);

        assertThat(masked).contains("-----REDACTED-PEM-BLOCK-----");
        assertThat(masked).doesNotContain("MIID");
    }

    @Test
    void mask_multiplePemBlocks_allRedacted() {
        // 실제 PEM 타입 (CERTIFICATE, PRIVATE KEY) — 영문 + 공백만.
        String log = "-----BEGIN CERTIFICATE-----\nfoo\n-----END CERTIFICATE-----\n"
                + "middle\n"
                + "-----BEGIN PRIVATE KEY-----\nbar\n-----END PRIVATE KEY-----";

        String masked = VmClusterBootstrapLogServiceImpl.maskSensitive(log);

        // 두 개 PEM block 모두 redacted — sentinel 2회 등장 확인 (split limit=-1 으로 trailing empty 보존).
        int sentinelCount = masked.split("-----REDACTED-PEM-BLOCK-----", -1).length - 1;
        assertThat(sentinelCount).isEqualTo(2);
        assertThat(masked).doesNotContain("foo");
        assertThat(masked).doesNotContain("bar");
        assertThat(masked).contains("middle");
    }

    @Test
    void mask_kubeadmToken_redacted() {
        // kubeadm token 형식: 6 chars . 16 chars (lowercase + digit).
        String log = "joinToken: abcdef.0123456789abcdef\nstatus: ok";

        String masked = VmClusterBootstrapLogServiceImpl.maskSensitive(log);

        assertThat(masked).contains("joinToken: REDACTED-TOKEN");
        assertThat(masked).contains("status: ok");
        assertThat(masked).doesNotContain("abcdef.0123456789abcdef");
    }

    @Test
    void mask_safeContent_unchanged() {
        // 정상적인 kubectl get pods 출력은 그대로 보존.
        String log = "NAME                       READY   STATUS    RESTARTS   AGE\n"
                + "kube-system   coredns-...   1/1     Running   0          5m\n"
                + "kube-system   etcd-master    1/1     Running   0          5m";

        String masked = VmClusterBootstrapLogServiceImpl.maskSensitive(log);

        assertThat(masked).isEqualTo(log);
    }

    @Test
    void mask_nullOrBlank_passthrough() {
        assertThat(VmClusterBootstrapLogServiceImpl.maskSensitive(null)).isNull();
        assertThat(VmClusterBootstrapLogServiceImpl.maskSensitive("")).isEmpty();
        assertThat(VmClusterBootstrapLogServiceImpl.maskSensitive("   ")).isEqualTo("   ");
    }

    @Test
    void mask_doesNotFalsePositive_ipAddresses() {
        // IP 주소나 hash 값 같은 일반적인 string 은 token 패턴 매칭 안 함.
        String log = "192.168.1.10 node-1 Ready\n10.0.0.5 node-2 Ready";

        String masked = VmClusterBootstrapLogServiceImpl.maskSensitive(log);

        assertThat(masked).isEqualTo(log);
    }
}
