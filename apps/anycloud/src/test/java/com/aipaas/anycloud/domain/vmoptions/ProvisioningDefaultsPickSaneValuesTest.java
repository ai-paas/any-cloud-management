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
 * 기본값은 현재 통과하는 값이어야 함.
 *
 * <p>목록의 첫 값을 그대로 사용하면 1vCPU ARM 인스턴스나 설치본에 없는 flavor 가 선택됨.
 * 해당 사실은 프로비저닝 중반에 드러나며 검증용 화면이 실패를 유발.
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
                        // 해당 설치본 미제공 flavor. 권장값의 상시 존재는 보장 불가.
                        .recommendedVmSpec("m1.large")
                        .build()));
        when(vmOptionsService.getRegions(anyString(), anyString()))
                .thenReturn(List.of(VmOptionRegion.builder()
                        .provider("OpenStack")
                        .id("RegionOne")
                        .name("RegionOne")
                        .build()));
        when(capacityProbe.firstWithCapacity(any(), anyString(), anyString(), any(), any()))
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
        // providerSpec.flavorName 이 우선이라 두 값 불일치 시 화면과 실제 생성 결과 상이.
        assertThat(defaults.providerSpec()).containsEntry("flavorName", "4-8-50");
    }

    @Test
    void aDefaultThatTheAccountCannotUseIsReplaced() {
        // 설치본 미제공 m1.large 를 그대로 전송하면 생성 단계에서 거절.
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
    void anS390xImageIsNotChosenForAnX86Instance() {
        /*
         * IBM 은 같은 Ubuntu 를 amd64 와 s390x 로 함께 제공하고 s390x 판이 이름순으로 앞선다.
         * 거름망이 arm 과 aarch64 만 보던 동안 s390x 가 기본값이 되어 생성 단계에서
         * "Image OS architecture s390x is not supported" 로 실패했다.
         */
        when(vmOptionsService.getSpecs(anyString(), anyString(), anyString(), any(), anyBoolean(), anyInt()))
                .thenReturn(List.of(spec("id-1", "bx2-2x8", 2, 8.0)));
        when(vmOptionsService.getConfigSchema(anyString(), anyString(), anyString()))
                .thenReturn(List.of(ProviderConfigKey.builder()
                        .key("anycloud-k8s:osImage")
                        .required(true)
                        .allowedOptions(List.of(
                                new ConfigOption(
                                        "ibm-ubuntu-24-04-4-minimal-s390x-2", "ibm-ubuntu-24-04-4-minimal-s390x-2"),
                                new ConfigOption(
                                        "ibm-ubuntu-24-04-5-minimal-amd64-1", "ibm-ubuntu-24-04-5-minimal-amd64-1")))
                        .build()));

        assertThat(openstack().osImage()).isEqualTo("ibm-ubuntu-24-04-5-minimal-amd64-1");
    }

    @Test
    void tooSmallInstancesAreNotOffered() {
        /*
         * kubeadm preflight 생략 설정이라 1vCPU 2GB 로도 생성은 성공. 이후 control-plane
         * 미기동으로 BOOTSTRAP 단계에서 실패.
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
        // 조건에 맞는 것이 없으면 그 조건을 함께 알려줘야 무엇을 바꿔야 할지 안다.
        assertThat(defaults.blockedReason()).contains("최소 2vCPU");
    }

    @Test
    void everySupportedProviderAppearsEvenWithoutACredential() {
        // 목록 누락 시 해당 CSP 미지원으로 해석. 사유를 함께 기록.
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
