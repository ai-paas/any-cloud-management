package com.aipaas.anycloud.domain.vmoptions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.vmoptions.api.ProviderConfigKey;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UX #6 — provider config schema 발견성 endpoint 회귀 보호.
 *
 * <p>{@code ProvisioningConfigRules} 와 schema 정합성 검증 — 두 곳이 분리되어 있으므로
 * 중요 키들이 양쪽에 있는지 확인.
 */
class ProviderConfigSchemaServiceImplTest extends AbstractUnitTest {

    private final ProviderConfigSchemaServiceImpl service = new ProviderConfigSchemaServiceImpl();

    @Test
    void awsSchema_containsCrossCuttingKeys() {
        List<ProviderConfigKey> schema = service.getSchema("AWS");

        assertThat(schema)
                .extracting(ProviderConfigKey::key)
                .contains(
                        "anycloud-k8s:masterInstanceType",
                        "anycloud-k8s:workerInstanceType",
                        "anycloud-k8s:masterCount",
                        "anycloud-k8s:workerCount",
                        "anycloud-k8s:kubernetesVersion",
                        "anycloud-k8s:enableIngress",
                        "anycloud-k8s:enableGpuOperator");
    }

    @Test
    void awsSchema_aliasCanonicalization_acceptsLowercase() {
        List<ProviderConfigKey> upper = service.getSchema("AWS");
        List<ProviderConfigKey> lower = service.getSchema("aws");

        assertThat(lower)
                .extracting(ProviderConfigKey::key)
                .containsExactlyElementsOf(
                        upper.stream().map(ProviderConfigKey::key).toList());
    }

    @Test
    void masterCount_hasOddOnlyAllowedValues() {
        ProviderConfigKey entry = findKey(service.getSchema("AWS"), "anycloud-k8s:masterCount");

        assertThat(entry.type()).isEqualTo("integer");
        assertThat(entry.defaultValue()).isEqualTo("1");
        assertThat(entry.allowedValues()).containsExactly("1", "3", "5", "7");
        assertThat(entry.description()).contains("HA");
        assertThat(entry.description()).contains("etcd");
    }

    @Test
    void booleanFlags_haveExplicitTrueFalseAllowedValues() {
        List<ProviderConfigKey> schema = service.getSchema("AWS");
        for (String key :
                List.of("anycloud-k8s:enableIngress", "anycloud-k8s:enableGpuOperator", "anycloud-k8s:dbEnabled")) {
            ProviderConfigKey entry = findKey(schema, key);
            assertThat(entry.type()).as(key + " type").isEqualTo("boolean");
            assertThat(entry.allowedValues()).as(key + " allowedValues").containsExactly("true", "false");
        }
    }

    @Test
    void gcpSchema_includesGcpProjectAsRequired() {
        List<ProviderConfigKey> schema = service.getSchema("GCP");

        ProviderConfigKey entry = findKey(schema, "anycloud-k8s:gcpProject");
        assertThat(entry.required()).isTrue();
    }

    @Test
    void azureSchema_includesAzureResourceGroupAsRequired() {
        ProviderConfigKey entry = findKey(service.getSchema("Azure"), "anycloud-k8s:azureResourceGroup");
        assertThat(entry.required()).isTrue();
    }

    @Test
    void openstackSchema_listsFlavorAndImage_andEitherNetworkKey() {
        List<ProviderConfigKey> schema = service.getSchema("openstack");

        assertThat(schema)
                .extracting(ProviderConfigKey::key)
                .contains(
                        "anycloud-k8s:openstackImageName",
                        "anycloud-k8s:openstackFlavorName",
                        "anycloud-k8s:openstackExternalNetworkId",
                        "anycloud-k8s:openstackFloatingIpPool");
        assertThat(findKey(schema, "anycloud-k8s:openstackImageName").required())
                .isTrue();
        assertThat(findKey(schema, "anycloud-k8s:openstackFlavorName").required())
                .isTrue();
    }

    @Test
    void ociSchema_listsCompartmentIdAsRequired() {
        ProviderConfigKey entry = findKey(service.getSchema("oci"), "anycloud-k8s:ociCompartmentId");
        assertThat(entry.required()).isTrue();
    }

    @Test
    void unsupportedProvider_returns400() {
        assertThatThrownBy(() -> service.getSchema("kubernetes-self-hosted"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("Unsupported provider");
    }

    @Test
    void allProviders_returnAtLeastCommonKeysCount() {
        // 모든 provider 에 cross-cutting 키 11개 (master/worker spec, master/worker count,
        // k8s version, pod/service cidr, joinToken, 3 boolean flags) 있음을 보장.
        for (String provider : List.of("AWS", "GCP", "Azure", "Alibaba", "OpenStack", "OCI", "DigitalOcean")) {
            assertThat(service.getSchema(provider)).as(provider).hasSizeGreaterThanOrEqualTo(11);
        }
    }

    private ProviderConfigKey findKey(List<ProviderConfigKey> schema, String key) {
        return schema.stream()
                .filter(e -> e.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Key not found: " + key));
    }
}
