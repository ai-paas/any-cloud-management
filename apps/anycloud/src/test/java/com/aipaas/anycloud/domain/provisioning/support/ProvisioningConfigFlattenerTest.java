package com.aipaas.anycloud.domain.provisioning.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.api.request.ClusterSpecRequest;
import com.aipaas.anycloud.domain.provisioning.api.request.VmCreateRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 중첩 요청 → Pulumi 평면 config 변환 회귀 보호. */
class ProvisioningConfigFlattenerTest {

    private VmCreateRequest request(ClusterSpecRequest spec, Map<String, Object> providerSpec) {
        VmCreateRequest r = new VmCreateRequest();
        r.setSpec(spec);
        r.setProviderSpec(providerSpec);
        return r;
    }

    @Test
    void flattensCommonSpecWithNamespace() {
        ClusterSpecRequest spec = new ClusterSpecRequest(
                "1.31", 1, 2, "t3.large", "t3.large", 50, "ubuntu-24.04", "ubuntu", null, true, false, null);

        Map<String, String> out = ProvisioningConfigFlattener.flatten(request(spec, null));

        assertThat(out)
                .containsEntry("anycloud-k8s:kubernetesVersion", "1.31")
                .containsEntry("anycloud-k8s:workerCount", "2")
                .containsEntry("anycloud-k8s:rootDiskSizeGb", "50")
                .containsEntry("anycloud-k8s:enableIngress", "true");
    }

    @Test
    void nestsNetworkFields() {
        ClusterSpecRequest spec = new ClusterSpecRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ClusterSpecRequest.NetworkSpecRequest("10.90.0.0/24", "10.244.0.0/16", "10.96.0.0/12"),
                null,
                null,
                null);

        Map<String, String> out = ProvisioningConfigFlattener.flatten(request(spec, null));

        assertThat(out)
                .containsEntry("anycloud-k8s:vpcCidr", "10.90.0.0/24")
                .containsEntry("anycloud-k8s:podCidr", "10.244.0.0/16")
                .containsEntry("anycloud-k8s:serviceCidr", "10.96.0.0/12");
    }

    @Test
    void prefixesProviderSpecKeys() {
        Map<String, Object> ps = Map.of("externalNetworkId", "3f8d3f36", "floatingIpPool", "external");

        Map<String, String> out = ProvisioningConfigFlattener.flatten(request(null, ps));

        assertThat(out)
                .containsEntry("anycloud-k8s:providerSpec.externalNetworkId", "3f8d3f36")
                .containsEntry("anycloud-k8s:providerSpec.floatingIpPool", "external");
    }

    @Test
    void skipsNullAndBlank() {
        // 빈 문자열을 넘기면 Defaults 가 채울 자리를 빈 값이 선점한다.
        ClusterSpecRequest spec =
                new ClusterSpecRequest("  ", null, null, null, null, null, null, null, null, null, null, null);

        Map<String, String> out = ProvisioningConfigFlattener.flatten(request(spec, Map.of()));

        assertThat(out).isEmpty();
    }

    @Test
    void emptyRequestProducesEmptyConfig() {
        assertThat(ProvisioningConfigFlattener.flatten(request(null, null))).isEmpty();
    }
}
