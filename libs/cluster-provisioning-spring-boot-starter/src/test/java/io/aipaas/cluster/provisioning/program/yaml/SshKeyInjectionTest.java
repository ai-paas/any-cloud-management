package io.aipaas.cluster.provisioning.program.yaml;

import static org.assertj.core.api.Assertions.assertThat;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.Defaults;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 전체 emitter 의 공개키 VM 주입 여부 검증.
 *
 * <p>주입 위치가 CSP 별로 달라(keyName, metadata, userAccount 등) 개별 테스트에서 누락되기 쉬움.
 * 누락 시 VM 은 정상 기동하고 bootstrap 의 SSH 접속만 실패해 원인 파악 지연.
 */
class SshKeyInjectionTest {

    private static final Map<String, Map<String, String>> PROVIDER_CONFIG = Map.of(
            "aws", Map.of(),
            "gcp", Map.of("providerSpec.project", "demo"),
            "alibaba",
                    Map.of(
                            "providerSpec.zone",
                            "ap-northeast-2a",
                            "osImage",
                            "ubuntu_24_04_x64_20G_alibase_20260828.vhd"),
            "oci", Map.of("providerSpec.compartmentId", "ocid1.compartment.oc1..demo", "osImage", "ocid1.image.demo"),
            "openstack",
                    Map.of(
                            "providerSpec.imageName", "ubuntu-24.04",
                            "providerSpec.flavorName", "m1.large",
                            "providerSpec.externalNetworkId", "net-1",
                            "providerSpec.floatingIpPool", "external"),
            "proxmox", Map.of("providerSpec.nodeName", "pve1"),
            "ibm", Map.of("providerSpec.zone", "jp-tok-1", "osImage", "ibm-ubuntu-24-04-4-minimal-amd64-7"));

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
