package com.aipaas.anycloud.domain.vmoptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.credential.CspCredentialEntity;
import com.aipaas.anycloud.domain.credential.CspCredentialRepository;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.api.ConfigOption;
import com.aipaas.anycloud.domain.vmoptions.api.ProviderConfigKey;
import com.aipaas.anycloud.domain.vmoptions.api.ProvisioningDefaults;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec;
import com.aipaas.anycloud.domain.vmoptions.internal.CapacityProbe;
import com.aipaas.anycloud.domain.vmoptions.internal.ProvisioningDefaultsServiceImpl;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 기본값은 "지금 통과하는 값" 이어야 한다.
 *
 * <p>목록의 첫 값을 그냥 쓰면 1vCPU ARM 인스턴스나 설치본에 없는 flavor 가 잡힌다. 그 사실은
 * 프로비저닝 중반에야 드러나고, 검증용 화면이 오히려 실패를 만든다.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class ProvisioningDefaultsPickSaneValuesTest extends AbstractUnitTest {

    @Mock
    VmOptionsService vmOptionsService;

    @Mock
    CspCredentialRepository credentialRepository;

    @Mock
    CapacityProbe capacityProbe;

    private ProvisioningDefaultsServiceImpl service;

    private static VmOptionSpec spec(String id, String name, int vcpu, double memoryGb) {
        return VmOptionSpec.builder()
                .id(id)
                .name(name)
                .vcpu(vcpu)
                .memoryGb(memoryGb)
                .build();
    }

    @BeforeEach
    void setUp() {
        service = new ProvisioningDefaultsServiceImpl(vmOptionsService, credentialRepository, capacityProbe);
        when(credentialRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(CspCredentialEntity.builder()
                        .id("cred-1")
                        .name("openstack-lab")
                        .provider("OpenStack")
                        .healthStatus("HEALTHY")
                        .build()));
        when(vmOptionsService.getProviders())
                .thenReturn(List.of(VmOptionProvider.builder()
                        .provider("OpenStack")
                        .displayName("OpenStack")
                        .recommendedRegion("RegionOne")
                        // 이 설치본에 없는 flavor. 권장값이 늘 존재한다고 볼 수 없다.
                        .recommendedVmSpec("m1.large")
                        .build()));
        when(vmOptionsService.getRegions(anyString(), anyString()))
                .thenReturn(List.of(VmOptionRegion.builder()
                        .provider("OpenStack")
                        .id("RegionOne")
                        .name("RegionOne")
                        .build()));
        when(capacityProbe.firstWithCapacity(any(), anyString(), anyString(), any()))
                .thenAnswer(call -> new CapacityProbe.SpecChoice(((List<String>) call.getArgument(3)).get(0), null));
    }

    private ProvisioningDefaults openstack() {
        return service.listDefaults(null).stream()
                .filter(d -> "OpenStack".equals(d.provider()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void theFlavorIsTheNameBecauseTheEmitterCannotUseTheUuid() {
        when(vmOptionsService.getSpecs(anyString(), anyString(), anyString(), any(), anyBoolean(), anyInt()))
                .thenReturn(List.of(spec("0127bc0e-2f07-4a84", "4-8-50", 4, 8.0)));
        when(vmOptionsService.getConfigSchema(anyString(), anyString(), anyString()))
                .thenReturn(List.of(ProviderConfigKey.builder()
                        .key("anycloud-k8s:providerSpec.flavorName")
                        .required(true)
                        .defaultValue("m1.large")
                        .allowedOptions(List.of(new ConfigOption("4-8-50", "4-8-50")))
                        .allowedValues(List.of("4-8-50"))
                        .build()));

        ProvisioningDefaults defaults = openstack();

        assertThat(defaults.masterInstanceType()).isEqualTo("4-8-50");
        // providerSpec.flavorName 이 우선이라 두 값이 갈리면 화면과 실제가 달라진다.
        assertThat(defaults.providerSpec()).containsEntry("flavorName", "4-8-50");
    }

    @Test
    void aDefaultThatTheAccountCannotUseIsReplaced() {
        // 설치본에 없는 m1.large 를 그대로 보내면 생성 도중 거절된다.
        when(vmOptionsService.getSpecs(anyString(), anyString(), anyString(), any(), anyBoolean(), anyInt()))
                .thenReturn(List.of(spec("id-1", "4-8-50", 4, 8.0)));
        when(vmOptionsService.getConfigSchema(anyString(), anyString(), anyString()))
                .thenReturn(List.of(ProviderConfigKey.builder()
                        .key("anycloud-k8s:providerSpec.imageName")
                        .required(true)
                        .defaultValue("ubuntu-22.04")
                        .allowedValues(List.of("ubuntu-24.04", "rocky-9"))
                        .build()));

        assertThat(openstack().providerSpec()).containsEntry("imageName", "ubuntu-24.04");
    }

    @Test
    void tooSmallInstancesAreNotOffered() {
        /*
         * kubeadm preflight 를 건너뛰게 해둬서 1vCPU 2GB 로도 생성은 된다. 그러고 나서
         * control-plane 이 뜨지 않아 BOOTSTRAP 에서 끝난다.
         */
        when(vmOptionsService.getSpecs(anyString(), anyString(), anyString(), any(), anyBoolean(), anyInt()))
                .thenReturn(List.of(spec("tiny", "1-2", 1, 2.0), spec("ok", "2-4", 2, 4.0)));
        when(vmOptionsService.getConfigSchema(anyString(), anyString(), anyString()))
                .thenReturn(List.of());

        assertThat(openstack().masterInstanceType()).isEqualTo("2-4");
    }

    @Test
    void aProviderWithNoUsableInstanceIsBlockedWithAReason() {
        when(vmOptionsService.getSpecs(anyString(), anyString(), anyString(), any(), anyBoolean(), anyInt()))
                .thenReturn(List.of(spec("tiny", "1-2", 1, 2.0)));
        when(vmOptionsService.getConfigSchema(anyString(), anyString(), anyString()))
                .thenReturn(List.of());

        ProvisioningDefaults defaults = openstack();

        assertThat(defaults.ready()).isFalse();
        assertThat(defaults.blockedReason()).contains("control-plane");
    }

    @Test
    void everySupportedProviderAppearsEvenWithoutACredential() {
        // 목록에서 빠지면 "이 CSP 는 지원하지 않는다" 로 읽힌다. 이유를 달아 남긴다.
        when(vmOptionsService.getConfigSchema(anyString(), anyString(), anyString()))
                .thenReturn(List.of());

        assertThat(service.listDefaults(null))
                .hasSize(SupportedProvisioningProvider.values().length)
                .filteredOn(d -> !"OpenStack".equals(d.provider()))
                .allSatisfy(d -> {
                    assertThat(d.ready()).isFalse();
                    assertThat(d.blockedReason()).contains("자격증명");
                });
    }
}
