package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import org.junit.jupiter.api.Test;

class GenericLinuxVmClusterBootstrapStrategyTest {

    private final GenericLinuxVmClusterBootstrapStrategy strategy = new GenericLinuxVmClusterBootstrapStrategy();

    @Test
    void addonInstall_keepsCniBecauseClusterCannotFormWithoutIt() {
        String script = strategy.buildAddonInstallCommand(
                VmClusterInternalRequestSnapshot.builder().build());
        assertThat(script).contains("calico");
    }

    @Test
    void addonInstall_noLongerInstallsGpu() {
        // GPU 는 GPU_OPERATOR 컴포넌트가 소유한다. 셸에 남기면 실패가 다시 || true 로 사라진다.
        String script = strategy.buildAddonInstallCommand(VmClusterInternalRequestSnapshot.builder()
                .enableGpuOperator(true)
                .build());
        assertThat(script).doesNotContain("gpu-operator");
        assertThat(script).doesNotContain("ubuntu-drivers");
    }

    @Test
    void addonInstall_noLongerInstallsIngress() {
        String script = strategy.buildAddonInstallCommand(
                VmClusterInternalRequestSnapshot.builder().enableIngress(true).build());
        assertThat(script).doesNotContain("ingress-nginx");
    }

    @Test
    void addonInstall_hasNoFailureSwallowingOrBlockingWait() {
        String script = strategy.buildAddonInstallCommand(VmClusterInternalRequestSnapshot.builder()
                .enableGpuOperator(true)
                .enableIngress(true)
                .build());
        assertThat(script).doesNotContain("kubectl wait");
        assertThat(script).doesNotContain("|| true");
    }
}
