package io.aipaas.cluster.provisioning.program.yaml;

import static org.assertj.core.api.Assertions.assertThat;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.Defaults;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 모든 emitter 가 생성한 공개키를 VM 에 주입하는지 확인한다.
 *
 * <p>주입 위치가 CSP 마다 달라(keyName, metadata, userAccount …) 개별 테스트에서 빠지기 쉽다.
 * 빠지면 VM 은 정상적으로 뜨고 bootstrap 의 SSH 접속만 실패해 원인이 늦게 드러난다.
 */
class SshKeyInjectionTest {

    private static final Map<String, Map<String, String>> PROVIDER_CONFIG = Map.of(
            "aws", Map.of(),
            "gcp", Map.of("providerSpec.project", "demo"),
            "azure", Map.of("providerSpec.resourceGroup", "demo-rg"),
            "oci", Map.of("providerSpec.compartmentId", "ocid1.compartment.oc1..demo", "osImage", "ocid1.image.demo"),
            "openstack",
                    Map.of(
                            "providerSpec.imageName", "ubuntu-24.04",
                            "providerSpec.flavorName", "m1.large",
                            "providerSpec.externalNetworkId", "net-1",
                            "providerSpec.floatingIpPool", "external"),
            "proxmox", Map.of("providerSpec.nodeName", "pve1"),
            "ibm", Map.of("providerSpec.zone", "us-south-1"));

    private String render(String provider) {
        Map<String, String> cfg = new HashMap<>(PROVIDER_CONFIG.get(provider));
        cfg.put("provider", provider);
        cfg.put("name", "demo");
        cfg.put("region", "r1");
        cfg.put("workerCount", "1");
        cfg.put("joinToken", "abcdef.0123456789abcdef");
        ClusterSpec spec = Defaults.applyProviderDefaults(ClusterSpec.from(cfg));

        PulumiProgram.Builder b = PulumiProgram.builder("anycloud-k8s");
        YamlEmitters.emit(b, spec);
        return b.build().toYaml();
    }

    @Test
    void everyProviderReferencesTheGeneratedPublicKey() {
        for (String provider : PROVIDER_CONFIG.keySet()) {
            String yaml = render(provider);

            assertThat(yaml).as("%s 가 생성한 공개키를 참조하지 않는다", provider).containsPattern("publicKeyOpenssh|sshKey\\.id");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyProviderCreatesThePrivateKeyResource() {
        for (String provider : PROVIDER_CONFIG.keySet()) {
            Map<String, Object> doc = (Map<String, Object>) new Yaml().load(render(provider));
            Map<String, Object> resources = (Map<String, Object>) doc.get("resources");

            assertThat(resources.values())
                    .as("%s 에 tls PrivateKey 리소스가 없다", provider)
                    .anySatisfy(r -> assertThat(((Map<String, Object>) r).get("type"))
                            .isEqualTo("tls:index/privateKey:PrivateKey"));
        }
    }
}
