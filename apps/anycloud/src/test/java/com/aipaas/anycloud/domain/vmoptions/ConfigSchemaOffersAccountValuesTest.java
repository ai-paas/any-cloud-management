package com.aipaas.anycloud.domain.vmoptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.credential.CspCredentialEntity;
import com.aipaas.anycloud.domain.credential.CspCredentialRepository;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.vmoptions.api.ProviderConfigKey;
import com.aipaas.anycloud.domain.vmoptions.api.VmOptionProvider;
import com.aipaas.anycloud.domain.vmoptions.internal.ProviderConfigSchemaServiceImpl;
import com.aipaas.anycloud.domain.vmoptions.internal.VmOptionsQueryServiceImpl;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 정적 schema 만 내려주면 포탈은 자유 입력 칸밖에 못 만든다.
 *
 * <p>IBM zone 처럼 계정마다 다른 값은 사용자가 명명 규칙을 외워 타이핑해야 하고, 틀린 값은
 * 프로비저닝 도중에야 드러난다. 자격증명이 주어지면 고를 수 있는 값을 함께 내려준다.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class ConfigSchemaOffersAccountValuesTest extends AbstractUnitTest {

    private static final String ZONE_KEY = "anycloud-k8s:providerSpec.zone";

    @Mock
    CspCredentialService cspCredentialService;

    @Mock
    CspCredentialRepository cspCredentialRepository;

    private VmOptionsQueryServiceImpl service;

    /** zone 만 값을 열거할 수 있는 provider. 실제 IBM 구현이 하는 일과 같은 계약이다. */
    private static final class ZoneAwareProvider implements VmOptionsProvider {
        @Override
        public SupportedProvisioningProvider getProvider() {
            return SupportedProvisioningProvider.IBM;
        }

        @Override
        public VmOptionProvider describe() {
            return VmOptionProvider.builder().provider("IBM").displayName("IBM").build();
        }

        @Override
        public List<com.aipaas.anycloud.domain.vmoptions.api.VmOptionRegion> listRegions() {
            return List.of();
        }

        @Override
        public List<com.aipaas.anycloud.domain.vmoptions.api.VmOptionSpec> listSpecs(
                String region, String keyword, boolean gpuOnly, int limit) {
            return List.of();
        }

        @Override
        public List<com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage> listImages(
                String region, String keyword, String architecture, String owner, int limit) {
            if (!"jp-tok".equals(region)) {
                return List.of();
            }
            return List.of(
                    com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage.builder()
                            .id("ibm-ubuntu-24-04-4-minimal-amd64-7")
                            .name("ibm-ubuntu-24-04-4-minimal-amd64-7")
                            .build(),
                    com.aipaas.anycloud.domain.vmoptions.api.VmOptionImage.builder()
                            .id("ibm-ubuntu-22-04-5-minimal-amd64-6")
                            .name("ibm-ubuntu-22-04-5-minimal-amd64-6")
                            .build());
        }

        @Override
        public List<String> listConfigOptions(String configKey, String region) {
            if ("providerSpec.zone".equals(configKey) && "jp-tok".equals(region)) {
                return List.of("jp-tok-1", "jp-tok-2");
            }
            // 조회가 CSP 오류로 끝나는 키. 실제로 OpenStack 의 외부망 조회가 그랬다.
            if ("providerSpec.resourceGroup".equals(configKey)) {
                throw new IllegalStateException("404 Not Found");
            }
            return List.of();
        }
    }

    @BeforeEach
    void setUp() {
        service = new VmOptionsQueryServiceImpl(
                List.of(new ZoneAwareProvider()),
                new VmOptionsProperties(),
                cspCredentialService,
                cspCredentialRepository,
                new ProviderConfigSchemaServiceImpl());
        when(cspCredentialRepository.findById(anyString()))
                .thenReturn(
                        Optional.of(CspCredentialEntity.builder().id("cred-1").build()));
        when(cspCredentialService.resolveEnvironment(anyString(), anyString()))
                .thenReturn(Map.of("IBMCLOUD_API_KEY", "x"));
    }

    private ProviderConfigKey keyOf(List<ProviderConfigKey> schema, String key) {
        return schema.stream().filter(k -> key.equals(k.key())).findFirst().orElseThrow();
    }

    @Test
    void theZoneKeyComesBackWithTheZonesTheAccountCanUse() {
        List<ProviderConfigKey> schema = service.listConfigSchema("IBM", "cred-1", "jp-tok");

        assertThat(keyOf(schema, ZONE_KEY).allowedValues()).containsExactly("jp-tok-1", "jp-tok-2");
    }

    @Test
    void withoutACredentialTheSchemaStaysStatic() {
        // 자격증명 없이 조회를 시도하면 CSP 호출이 실패하거나 401 을 받는다. 자유 입력으로 둔다.
        List<ProviderConfigKey> schema = service.listConfigSchema("IBM", null, "jp-tok");

        assertThat(keyOf(schema, ZONE_KEY).allowedValues()).isNullOrEmpty();
    }

    @Test
    void withoutARegionTheZoneKeyStaysFreeText() {
        List<ProviderConfigKey> schema = service.listConfigSchema("IBM", "cred-1", null);

        assertThat(keyOf(schema, ZONE_KEY).allowedValues()).isNullOrEmpty();
    }

    @Test
    void aFailedLookupLeavesTheRestOfTheSchemaIntact() {
        /*
         * 조회 예외가 그대로 올라오면 폼이 통째로 비어 "설정할 것이 없는 CSP" 로 보인다 —
         * OpenStack 이 외부망 조회 404 하나로 필드 15개를 전부 잃었다.
         */
        List<ProviderConfigKey> schema = service.listConfigSchema("IBM", "cred-1", "jp-tok");

        assertThat(schema).isNotEmpty();
        assertThat(keyOf(schema, "anycloud-k8s:providerSpec.resourceGroup").allowedValues())
                .isNullOrEmpty();
        assertThat(keyOf(schema, ZONE_KEY).allowedValues()).containsExactly("jp-tok-1", "jp-tok-2");
    }

    @Test
    void theImageKeyComesBackWithImagesTheAccountCanUse() {
        // 이미지는 조회 경로가 따로 있어 필수인데도 고를 값이 없었다. OCID 를 받아 적게 둘 수 없다.
        List<ProviderConfigKey> schema = service.listConfigSchema("IBM", "cred-1", "jp-tok");

        assertThat(keyOf(schema, "anycloud-k8s:osImage").allowedValues())
                .containsExactly("ibm-ubuntu-24-04-4-minimal-amd64-7", "ibm-ubuntu-22-04-5-minimal-amd64-6");
    }

    @Test
    void staticConstraintsAreNotOverwritten() {
        // masterCount 는 etcd quorum 때문에 홀수만 받는다. 조회 결과로 덮으면 그 제약이 사라진다.
        List<ProviderConfigKey> schema = service.listConfigSchema("IBM", "cred-1", "jp-tok");

        assertThat(keyOf(schema, "anycloud-k8s:masterCount").allowedValues()).isNotEmpty();
    }
}
