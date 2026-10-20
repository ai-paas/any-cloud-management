package com.aipaas.anycloud.domain.provisioning.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.aipaas.anycloud.domain.provisioning.bootstrap.providers.AlibabaVmClusterBootstrapStrategy;
import com.aipaas.anycloud.domain.provisioning.bootstrap.providers.AwsVmClusterBootstrapStrategy;
import com.aipaas.anycloud.domain.provisioning.bootstrap.providers.GcpVmClusterBootstrapStrategy;
import com.aipaas.anycloud.domain.provisioning.bootstrap.providers.GenericLinuxVmClusterBootstrapStrategy;
import com.aipaas.anycloud.domain.provisioning.bootstrap.providers.IbmVmClusterBootstrapStrategy;
import com.aipaas.anycloud.domain.provisioning.bootstrap.providers.OciVmClusterBootstrapStrategy;
import com.aipaas.anycloud.domain.provisioning.bootstrap.providers.OpenStackVmClusterBootstrapStrategy;
import com.aipaas.anycloud.domain.provisioning.bootstrap.providers.ProxmoxVmClusterBootstrapStrategy;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 인프라를 다 만든 뒤에야 전략이 없다는 것을 알면 늦다.
 *
 * <p>PROVISION 이 끝난 상태에서 BOOTSTRAP 이 즉시 실패하므로, 만들어진 인스턴스가 과금되는 채로
 * 남는다. IBM 이 실제로 그랬다. {@code No VM cluster bootstrap strategy for provider IBM}.
 */
class EveryProviderHasABootstrapStrategyTest extends AbstractUnitTest {

    /** Spring 주입 목록과 동일. CSP 추가 시 이 목록에도 등록 필요. */
    private final VmClusterBootstrapStrategyResolver resolver = new VmClusterBootstrapStrategyResolver(List.of(
            new AwsVmClusterBootstrapStrategy(),
            new OpenStackVmClusterBootstrapStrategy(),
            new GcpVmClusterBootstrapStrategy(),
            new AlibabaVmClusterBootstrapStrategy(),
            new OciVmClusterBootstrapStrategy(),
            new IbmVmClusterBootstrapStrategy(),
            new ProxmoxVmClusterBootstrapStrategy(),
            new GenericLinuxVmClusterBootstrapStrategy()));

    @ParameterizedTest
    @EnumSource(SupportedProvisioningProvider.class)
    void everySupportedProviderResolvesToAStrategy(SupportedProvisioningProvider provider) {
        assertThatCode(() -> resolver.resolve(provider.getCanonicalName()))
                .as("%s 로 만든 인프라가 부트스트랩 직전에 버려진다", provider)
                .doesNotThrowAnyException();
    }

    @Test
    void ibmUsesTheLinuxStrategyBecauseItBootsUbuntu() {
        assertThat(resolver.resolve("IBM")).isInstanceOf(IbmVmClusterBootstrapStrategy.class);
    }

    @Test
    void anUnknownProviderFallsBackToTheLinuxStrategy() {
        // 기본 전략이 마지막에 위치해야 신규 CSP 도 매칭.
        assertThat(resolver.resolve("")).isInstanceOf(GenericLinuxVmClusterBootstrapStrategy.class);
    }
}
