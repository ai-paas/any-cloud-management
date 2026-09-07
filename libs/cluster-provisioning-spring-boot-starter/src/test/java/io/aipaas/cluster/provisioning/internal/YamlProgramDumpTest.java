package io.aipaas.cluster.provisioning.internal;

import io.aipaas.cluster.provisioning.api.ProvisioningRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 생성된 Pulumi.yaml 을 파일로 떨궈 CLI 검증에 쓴다. 어서션이 없는 진단용 테스트 —
 * {@code ANYCLOUD_DUMP_YAML=<경로>} 를 줄 때만 동작한다.
 */
class YamlProgramDumpTest {

    @Test
    void dumpOpenstackProgramWhenRequested() throws IOException {
        String target = System.getenv("ANYCLOUD_DUMP_YAML");
        if (target == null || target.isBlank()) {
            return;
        }
        Map<String, String> config = new HashMap<>();
        config.put(
                "providerSpec.externalNetworkId", envOr("ANYCLOUD_OS_EXT_NET", "00000000-0000-0000-0000-000000000000"));
        config.put("providerSpec.floatingIpPool", envOr("ANYCLOUD_OS_FIP_POOL", "public"));
        config.put("providerSpec.imageName", "ubuntu-24.04");
        config.put("providerSpec.flavorName", envOr("ANYCLOUD_OS_FLAVOR", "m1.large"));
        config.put("workerCount", "2");
        config.put("joinToken", "abcdef.0123456789abcdef");
        config.put("vpcCidr", envOr("ANYCLOUD_VPC_CIDR", "10.42.0.0/16"));
        config.put("providerSpec.project", envOr("ANYCLOUD_GCP_PROJECT", "demo-project"));
        config.put("providerSpec.compartmentId", envOr("ANYCLOUD_OCI_COMPARTMENT", "ocid1.compartment.oc1..demo"));
        config.put("providerSpec.resourceGroup", envOr("ANYCLOUD_AZURE_RG", "demo-rg"));
        config.put("osImage", envOr("ANYCLOUD_OS_IMAGE_ID", ""));

        ProvisioningRequest request = new ProvisioningRequest();
        request.setProvider(envOr("ANYCLOUD_DUMP_PROVIDER", "openstack"));
        request.setClusterName("yaml-smoke");
        request.setEnvironment("dev");
        request.setRegion("RegionOne");
        request.setConfig(config);

        Files.writeString(
                Path.of(target), YamlProgramAssembler.assemble(request).toYaml(), StandardCharsets.UTF_8);
    }

    private static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
