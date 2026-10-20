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
        config.put("providerSpec.externalNetworkId", "ext-net-id");
        config.put("providerSpec.floatingIpPool", "public");
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
    void supportIsCaseInsensitiveAndClosed() {
        // 전환된 CSP 판정은 등록 목록 기준. 이 지점에 목록을 복제하면 전환마다 수정 필요.
        assertThat(YamlEmitters.supports("openstack")).isTrue();
        assertThat(YamlEmitters.supports("OpenStack")).isTrue();
        assertThat(YamlEmitters.supports("nonesuch")).isFalse();
        // canonical(null) 은 aws 로 처리. 이 지점의 null 검사는 해당 규칙을 중복 고정.
    }

    @Test
    @SuppressWarnings("unchecked")
    void assembleProducesYamlRuntimeProgram() {
        Map<String, Object> doc = new Yaml()
                .load(YamlProgramAssembler.assemble(request("openstack")).toYaml());

        assertThat(doc.get("runtime")).isEqualTo("yaml");
        assertThat(doc.get("name")).isEqualTo("anycloud-k8s");
        assertThat((Map<String, Object>) doc.get("resources")).containsKeys("net", "master", "worker-1", "worker-2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestFieldsReachTheSpec() {
        // provider, name, environment, region 은 config map 이 아닌 request 필드 유래.
        // applyConfig 와 동일 소스를 참조해야 스택 config 와 프로그램이 일치.
        Map<String, Object> doc = new Yaml()
                .load(YamlProgramAssembler.assemble(request("openstack")).toYaml());
        Map<String, Object> outputs = (Map<String, Object>) doc.get("outputs");

        assertThat(outputs.get("provider")).isEqualTo("openstack");
        assertThat(outputs.get("clusterName")).isEqualTo("demo");
    }

    @Test
    void unsupportedProviderIsRejected() {
        // emitter 미구현 CSP 가 YAML 경로로 진입하면 즉시 통보.
        assertThatThrownBy(() -> YamlProgramAssembler.assemble(request("nonesuch")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nonesuch");
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyProgramNeedsNoResources() {
        // outputs 조회와 destroy 는 리소스 정의 불필요.
        Map<String, Object> doc =
                new Yaml().load(YamlProgramAssembler.emptyProgram().toYaml());

        assertThat(doc.get("runtime")).isEqualTo("yaml");
        assertThat((Map<String, Object>) doc.get("resources")).isEmpty();
        assertThat(doc).doesNotContainKey("outputs");
    }
}
