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
        config.put("openstackExternalNetworkId", "00000000-0000-0000-0000-000000000000");
        config.put("openstackFloatingIpPool", "public");
        config.put("openstackImageName", "ubuntu-24.04");
        config.put("openstackFlavorName", "m1.large");
        config.put("workerCount", "2");
        config.put("joinToken", "abcdef.0123456789abcdef");

        ProvisioningRequest request = new ProvisioningRequest();
        request.setProvider("openstack");
        request.setClusterName("yaml-smoke");
        request.setEnvironment("dev");
        request.setRegion("RegionOne");
        request.setConfig(config);

        Files.writeString(
                Path.of(target), YamlProgramAssembler.assemble(request).toYaml(), StandardCharsets.UTF_8);
    }
}
