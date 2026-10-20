package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * 준비 대기에 상한 적용 필요.
 *
 * <p>cloud-init 실패 시 containerd 와 kubelet 미기동. 상한 부재 시 SSH 세션이 유지돼 워크플로가
 * BOOTSTRAPPING 에 정지한 채 로그도 미기록. 17분 경과 후에야 운영자가 검출한 사례 발생.
 */
class GenericLinuxBootstrapWaitTest extends AbstractUnitTest {

    private final GenericLinuxVmClusterBootstrapStrategy strategy = new GenericLinuxVmClusterBootstrapStrategy();

    @Test
    void waitLoopsAreBounded() {
        String cmd = strategy.waitForPreparationCommand();

        assertThat(cmd).as("상한 없는 until 루프가 남아 있다").doesNotContainPattern("until [^;]+; do sleep \\d+; done");
    }

    @Test
    void waitReportsWhyItGaveUp() {
        // 사유 없이 실패하면 원인이 SSH 인지 cloud-init 인지 구분 불가.
        String cmd = strategy.waitForPreparationCommand();

        assertThat(cmd).contains("cloud-init");
        assertThat(cmd).containsIgnoringCase("timed out");
    }

    @Test
    void stillWaitsForContainerdAndKubelet() {
        // 상한 적용 과정에서 대기를 제거하면 kubeadm 이 설치 전에 실행.
        String cmd = strategy.waitForPreparationCommand();

        assertThat(cmd).contains("containerd").contains("kubelet");
    }
}
