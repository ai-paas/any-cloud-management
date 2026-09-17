package io.aipaas.cluster.provisioning.program.yaml;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PulumiProgramTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String yaml) {
        return new Yaml().load(yaml);
    }

    @Test
    void declaresYamlRuntime() {
        // runtime 이 yaml 이어야 CLI 가 Pulumi.yaml 을 프로그램으로 해석한다.
        Map<String, Object> doc =
                parse(PulumiProgram.builder("anycloud-k8s").build().toYaml());

        assertThat(doc.get("name")).isEqualTo("anycloud-k8s");
        assertThat(doc.get("runtime")).isEqualTo("yaml");
    }

    @Test
    void emptyProgramHasEmptyResources() {
        // destroy 는 리소스 정의가 필요 없다. resources 키 자체가 없으면 CLI 가 거부한다.
        Map<String, Object> doc =
                parse(PulumiProgram.builder("anycloud-k8s").build().toYaml());

        assertThat(doc).containsKey("resources");
        assertThat((Map<?, ?>) doc.get("resources")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void resourceCarriesTypeAndProperties() {
        String yaml = PulumiProgram.builder("p")
                .resource("net", "openstack:networking/network:Network", Map.of("name", "demo-net"))
                .build()
                .toYaml();

        Map<String, Object> resources = (Map<String, Object>) parse(yaml).get("resources");
        Map<String, Object> net = (Map<String, Object>) resources.get("net");

        assertThat(net.get("type")).isEqualTo("openstack:networking/network:Network");
        assertThat((Map<String, Object>) net.get("properties")).containsEntry("name", "demo-net");
    }

    @Test
    void resourceOptionsAreEmittedOnlyWhenPresent() {
        // 빈 options 를 내보내면 CLI 가 파싱은 하지만 diff 노이즈가 된다.
        String withOpts = PulumiProgram.builder("p")
                .resource("a", "t", Map.of(), Map.of("dependsOn", List.of("${b}")))
                .build()
                .toYaml();
        String withoutOpts =
                PulumiProgram.builder("p").resource("a", "t", Map.of()).build().toYaml();

        assertThat(withOpts).contains("dependsOn");
        assertThat(withoutOpts).doesNotContain("options");
    }

    @Test
    void outputsArePreservedInDeclarationOrder() {
        // 순서가 안정되면 두 스택의 Pulumi.yaml 을 diff 해서 차이를 바로 볼 수 있다.
        String yaml = PulumiProgram.builder("p")
                .output("provider", "openstack")
                .output("clusterName", "demo")
                .output("apiServerUrl", "https://1.2.3.4:6443")
                .build()
                .toYaml();

        assertThat(yaml.indexOf("provider")).isLessThan(yaml.indexOf("clusterName"));
        assertThat(yaml.indexOf("clusterName")).isLessThan(yaml.indexOf("apiServerUrl"));
    }

    @Test
    void resourcesArePreservedInDeclarationOrder() {
        String yaml = PulumiProgram.builder("p")
                .resource("first", "t", Map.of())
                .resource("second", "t", Map.of())
                .build()
                .toYaml();

        assertThat(yaml.indexOf("first")).isLessThan(yaml.indexOf("second"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void specialCharactersSurviveRoundTrip() {
        // user-data 는 개행, 따옴표, ${} 가 섞인다. 문자열 조립이었다면 여기서 깨진다.
        String userData = "#!/bin/bash\nset -euxo pipefail\necho \"hello ${NAME}\"\napt-get install -y 'a b'\n";
        String yaml = PulumiProgram.builder("p")
                .resource("vm", "t", Map.of("userData", userData))
                .build()
                .toYaml();

        Map<String, Object> resources = (Map<String, Object>) parse(yaml).get("resources");
        Map<String, Object> vm = (Map<String, Object>) resources.get("vm");

        assertThat((Map<String, Object>) vm.get("properties")).containsEntry("userData", userData);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedStructuresSurviveRoundTrip() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("rules", List.of(Map.of("port", 22), Map.of("port", 6443)));
        String yaml =
                PulumiProgram.builder("p").resource("sg", "t", nested).build().toYaml();

        Map<String, Object> sg =
                (Map<String, Object>) ((Map<String, Object>) parse(yaml).get("resources")).get("sg");
        List<Map<String, Object>> rules =
                (List<Map<String, Object>>) ((Map<String, Object>) sg.get("properties")).get("rules");

        assertThat(rules).hasSize(2);
        assertThat(rules.get(1)).containsEntry("port", 6443);
    }
}
