package io.aipaas.cluster.provisioning.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aipaas.cluster.provisioning.api.ProvisioningRequest;
import io.aipaas.cluster.provisioning.program.yaml.YamlEmitters;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class YamlProgramAssemblerTest {

    private ProvisioningRequest request(String provider) {
        Map<String, String> config = new HashMap<>();
        config.put("openstackExternalNetworkId", "ext-net-id");
        config.put("openstackFloatingIpPool", "public");
        config.put("workerCount", "2");
        config.put("joinToken", "abcdef.0123456789abcdef");

        ProvisioningRequest req = new ProvisioningRequest();
        req.setProvider(provider);
        req.setClusterName("demo");
        req.setEnvironment("dev");
        req.setRegion("RegionOne");
        req.setConfig(config);
        return req;
    }

    @Test
    void openstackIsSupportedOthersAreNot() {
        // 나머지 CSP 는 아직 inline 프로그램으로 돈다. 한 번에 하나씩만 위험에 노출한다.
        assertThat(YamlEmitters.supports("openstack")).isTrue();
        assertThat(YamlEmitters.supports("OpenStack")).isTrue();
        assertThat(YamlEmitters.supports("aws")).isFalse();
        assertThat(YamlEmitters.supports("gcp")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void assembleProducesYamlRuntimeProgram() {
        Map<String, Object> doc = new Yaml().load(YamlProgramAssembler.assemble(request("openstack")).toYaml());

        assertThat(doc.get("runtime")).isEqualTo("yaml");
        assertThat(doc.get("name")).isEqualTo("anycloud-k8s");
        assertThat((Map<String, Object>) doc.get("resources")).containsKeys("net", "master", "worker-1", "worker-2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestFieldsReachTheSpec() {
        // provider/name/environment/region 은 config map 이 아니라 request 필드로 온다.
        // applyConfig 와 같은 소스를 봐야 스택 config 와 프로그램이 어긋나지 않는다.
        Map<String, Object> doc = new Yaml().load(YamlProgramAssembler.assemble(request("openstack")).toYaml());
        Map<String, Object> outputs = (Map<String, Object>) doc.get("outputs");

        assertThat(outputs.get("provider")).isEqualTo("openstack");
        assertThat(outputs.get("clusterName")).isEqualTo("demo");
    }

    @Test
    void unsupportedProviderIsRejected() {
        // 아직 emitter 가 없는 CSP 로 YAML 경로에 들어오면 즉시 알린다.
        assertThatThrownBy(() -> YamlProgramAssembler.assemble(request("aws")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aws");
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyProgramNeedsNoResources() {
        // outputs 조회와 destroy 는 리소스 정의가 필요 없다.
        Map<String, Object> doc = new Yaml().load(YamlProgramAssembler.emptyProgram().toYaml());

        assertThat(doc.get("runtime")).isEqualTo("yaml");
        assertThat((Map<String, Object>) doc.get("resources")).isEmpty();
        assertThat(doc).doesNotContainKey("outputs");
    }
}
