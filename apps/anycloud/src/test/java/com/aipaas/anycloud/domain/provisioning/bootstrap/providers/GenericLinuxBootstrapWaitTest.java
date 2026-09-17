package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * 준비 대기에는 상한이 있어야 한다.
 *
 * <p>cloud-init 이 실패하면 containerd 와 kubelet 이 영영 오지 않는다. 상한이 없으면 SSH 세션이
 * 계속 살아 있어 워크플로가 BOOTSTRAPPING 에서 멈춘 채 로그도 남지 않는다. 실제로 17분을 조용히
 * 기다린 뒤에야 사람이 알아챈 적이 있다.
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
        // 그냥 실패하면 원인이 SSH 인지 cloud-init 인지 구분되지 않는다.
        String cmd = strategy.waitForPreparationCommand();

        assertThat(cmd).contains("cloud-init");
        assertThat(cmd).containsIgnoringCase("timed out");
    }

    @Test
    void stillWaitsForContainerdAndKubelet() {
        // 상한을 넣다가 대기 자체를 없애면 kubeadm 이 설치 전에 돈다.
        String cmd = strategy.waitForPreparationCommand();

        assertThat(cmd).contains("containerd").contains("kubelet");
    }
}
