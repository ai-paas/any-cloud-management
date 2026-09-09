package io.aipaas.cluster.provisioning.program.yaml;

import static org.assertj.core.api.Assertions.assertThat;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.Defaults;
import io.aipaas.cluster.provisioning.program.K8sConstants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

/**
 * emitter 가 provider 무관하게 지켜야 하는 것들.
 *
 * <p>개별 emitter 테스트는 자기 자신만 본다. 그래서 6개 provider 에 있고 1개에만 없는 종류의 누락을
 * 잡지 못한다 — Proxmox 공개키 미주입이 그렇게 남아 있었다. 여기 있는 검사는 전 provider 를 한 번에
 * 돌아 새 CSP 를 추가할 때도 자동으로 걸린다.
 */
class ProviderContractTest {

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

    /** Proxmox 는 하이퍼바이저라 네트워크를 만들지 않는다. 방화벽은 운영자가 노드에서 관리한다. */
    private static final List<String> NO_FIREWALL = List.of("proxmox");

    static List<String> providers() {
        return PROVIDER_CONFIG.keySet().stream().sorted().toList();
    }

    private ClusterSpec spec(String provider) {
        Map<String, String> cfg = new HashMap<>(PROVIDER_CONFIG.get(provider));
        cfg.put("provider", provider);
        cfg.put("name", "demo");
        cfg.put("region", "r1");
        cfg.put("workerCount", "1");
        cfg.put("joinToken", "abcdef.0123456789abcdef");
        return Defaults.applyProviderDefaults(ClusterSpec.from(cfg));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doc(String provider) {
        PulumiProgram.Builder b = PulumiProgram.builder("anycloud-k8s");
        YamlEmitters.emit(b, spec(provider));
        return (Map<String, Object>) new Yaml().load(b.build().toYaml());
    }

    /**
     * 방화벽 리소스만 추린다. 전체 리소스를 보면 VPC 가 cidrBlock 으로 같은 문자열을 갖고 있어
     * 규칙이 없어도 검사가 통과한다 — 실제로 그렇게 헛돌던 것을 변형 검증으로 잡았다.
     */
    @SuppressWarnings("unchecked")
    private List<String> firewallEntries(String provider) {
        Map<String, Object> resources = (Map<String, Object>) doc(provider).get("resources");
        List<String> out = new ArrayList<>();
        resources.forEach((name, raw) -> {
            Map<String, Object> resource = (Map<String, Object>) raw;
            String type = String.valueOf(resource.get("type"));
            // 이름이 provider 마다 다르다 — securityGroup, secGroup, firewall, securityList.
            String lower = type.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("secgroup")
                    || lower.contains("securitygroup")
                    || lower.contains("securityrule")
                    || lower.contains("securitylist")
                    || lower.contains("firewall")) {
                collect(resource.get("properties"), out);
            }
        });
        return out;
    }

    /** 중첩 구조가 provider 마다 달라 값 비교 대신 평탄화해서 본다. */
    private List<String> flatten(Object node) {
        List<String> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private void collect(Object node, List<String> out) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                out.add(String.valueOf(k) + "=" + (v instanceof String s ? s : ""));
                collect(v, out);
            });
        } else if (node instanceof List<?> list) {
            list.forEach(v -> collect(v, out));
        } else if (node != null) {
            out.add(String.valueOf(node));
        }
    }

    @ParameterizedTest
    @MethodSource("providers")
    void fulfillsStackOutputContract(String provider) {
        // 이 키 목록이 ProvisioningService.stackOutputs() 의 계약이다. 하나라도 빠지면
        // ProvisioningResultMapper, VmClusterNodeResolver 가 깨진다.
        @SuppressWarnings("unchecked")
        Map<String, Object> outputs = (Map<String, Object>) doc(provider).get("outputs");

        assertThat(outputs)
                .as("%s 의 stack output 계약", provider)
                .containsKeys(
                        "provider",
                        "clusterName",
                        "vpcId",
                        "masterInstanceId",
                        "masterPublicIp",
                        "masterPrivateIp",
                        "apiServerUrl",
                        "sshPrivateKeyPem",
                        "kubeconfigRemotePath",
                        "nodes");
    }

    @ParameterizedTest
    @MethodSource("providers")
    void outputsCarryNoUnresolvedReference(String provider) {
        // ${x} 가 남으면 소비자가 IP 대신 문자열을 받는다.
        @SuppressWarnings("unchecked")
        Map<String, Object> outputs = (Map<String, Object>) doc(provider).get("outputs");

        outputs.forEach((key, value) -> {
            if (value instanceof String text && text.contains("${")) {
                assertThat(text)
                        .as("%s 의 %s 가 리소스를 가리키지 않는다", provider, key)
                        .containsPattern("\\$\\{[a-zA-Z0-9_.\\-]+\\.[a-zA-Z0-9_.\\[\\]]+\\}");
            }
        });
    }

    @ParameterizedTest
    @MethodSource("providers")
    void opensSshAndApiServerPorts(String provider) {
        if (NO_FIREWALL.contains(provider)) {
            return;
        }
        String joined = String.join(" ", firewallEntries(provider));

        assertThat(joined)
                .as("%s 가 SSH %d 를 열지 않는다", provider, K8sConstants.PORT_SSH)
                .contains(String.valueOf(K8sConstants.PORT_SSH));
        assertThat(joined)
                .as("%s 가 kube-apiserver %d 를 열지 않는다", provider, K8sConstants.PORT_KUBE_API_SERVER)
                .contains(String.valueOf(K8sConstants.PORT_KUBE_API_SERVER));
    }

    @ParameterizedTest
    @MethodSource("providers")
    void opensNodePortRange(String provider) {
        if (NO_FIREWALL.contains(provider)) {
            return;
        }
        String joined = String.join(" ", firewallEntries(provider));

        assertThat(joined)
                .as("%s 가 NodePort 범위를 열지 않는다", provider)
                .contains(String.valueOf(K8sConstants.NODE_PORT_MIN));
    }

    @ParameterizedTest
    @MethodSource("providers")
    void opensIntraNetworkTraffic(String provider) {
        if (NO_FIREWALL.contains(provider)) {
            return;
        }
        ClusterSpec spec = spec(provider);

        assertThat(firewallEntries(provider))
                .as("%s 의 방화벽에 VPC 내부(%s) 를 여는 규칙이 없다", provider, spec.vpcCidr())
                .anySatisfy(entry -> assertThat(entry).contains(spec.vpcCidr()));
    }

    /**
     * Calico 기본값 {@code ipipMode=Always} 가 노드 간 파드 트래픽을 IP protocol 4 로 감싼다. TCP/UDP 만
     * 열면 노드는 Ready 인데 파드 통신만 죽는다.
     *
     * <p>표현이 provider 마다 달라 하나로 묶을 수 없다. IBM 은 필드를 생략해 all 을 뜻하므로 값 검사가
     * 아니라 부재 검사다. 새 CSP 를 추가하면 여기 항목을 채워야 {@code everyProviderDeclaresIpipMarker}
     * 가 통과한다.
     */
    private static final Map<String, String> IPIP_MARKER = Map.of(
            "aws", "protocol=4",
            "oci", "protocol=4",
            "openstack", "protocol=4",
            "gcp", "protocol=ipip",
            "azure", "protocol=*",
            "ibm", "");

    @ParameterizedTest
    @MethodSource("providers")
    void allowsCalicoIpip(String provider) {
        if (NO_FIREWALL.contains(provider)) {
            return;
        }
        String marker = IPIP_MARKER.get(provider);
        List<String> entries = firewallEntries(provider);

        if (marker.isEmpty()) {
            // IBM 은 protocol 필드를 생략해 all 을 뜻한다. tcp/udp/icmp 중 하나라도 붙으면 IPIP 가 막힌다.
            assertThat(entries)
                    .as("%s 의 내부 규칙이 protocol 을 특정해 IPIP 를 막는다", provider)
                    .noneSatisfy(entry -> assertThat(entry).startsWith("icmp="));
            return;
        }
        assertThat(entries)
                .as("%s 가 IPIP 를 여는 규칙(%s)을 만들지 않는다", provider, marker)
                .contains(marker);
    }

    @Test
    void everyProviderDeclaresIpipMarker() {
        // emitter 를 추가하고 마커를 안 넣으면 IPIP 검사에서 조용히 빠진다.
        List<String> needMarker = YamlEmitters.supported().stream()
                .filter(p -> !NO_FIREWALL.contains(p))
                .toList();

        assertThat(IPIP_MARKER.keySet()).containsExactlyInAnyOrderElementsOf(needMarker);
    }

    @Test
    void everyRegisteredProviderIsCovered() {
        // emitter 를 추가하고 여기 config 를 안 넣으면 검사에서 조용히 빠진다.
        assertThat(PROVIDER_CONFIG.keySet()).containsExactlyInAnyOrderElementsOf(YamlEmitters.supported());
    }
}
