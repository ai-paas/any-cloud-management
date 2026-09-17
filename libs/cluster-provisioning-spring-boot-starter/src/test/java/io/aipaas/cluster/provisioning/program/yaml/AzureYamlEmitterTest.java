package io.aipaas.cluster.provisioning.program.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.Defaults;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Azure YAML 프로그램 회귀 보호. */
class AzureYamlEmitterTest {

    private Map<String, String> cfg() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("provider", "azure");
        cfg.put("name", "demo");
        cfg.put("region", "koreacentral");
        cfg.put("workerCount", "1");
        cfg.put("joinToken", "abcdef.0123456789abcdef");
        cfg.put("providerSpec.resourceGroup", "demo-rg");
        return cfg;
    }

    private ClusterSpec spec(Map<String, String> cfg) {
        return Defaults.applyProviderDefaults(ClusterSpec.from(cfg));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doc(Map<String, String> cfg) {
        PulumiProgram.Builder b = PulumiProgram.builder("anycloud-k8s");
        StandardOutputs.NodeRefs refs = new AzureYamlEmitter().emit(b, spec(cfg));
        StandardOutputs.apply(b, spec(cfg), refs);
        return (Map<String, Object>) new Yaml().load(b.build().toYaml());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> res(String name) {
        return (Map<String, Object>) ((Map<String, Object>) doc(cfg()).get("resources")).get(name);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> props(String name) {
        return (Map<String, Object>) res(name).get("properties");
    }

    @Test
    @SuppressWarnings("unchecked")
    void emitsResourceGroupNetworkAndNodes() {
        Map<String, Object> resources = (Map<String, Object>) doc(cfg()).get("resources");

        assertThat(resources)
                .containsKeys("sshKey", "rg", "vnet", "subnet", "nsg", "ip-master", "nic-master", "master", "worker-1");
    }

    @Test
    void usesSchemaVerifiedTypeTokens() {
        assertThat(res("rg").get("type")).isEqualTo("azure-native:resources:ResourceGroup");
        assertThat(res("vnet").get("type")).isEqualTo("azure-native:network:VirtualNetwork");
        assertThat(res("subnet").get("type")).isEqualTo("azure-native:network:Subnet");
        assertThat(res("nsg").get("type")).isEqualTo("azure-native:network:NetworkSecurityGroup");
        assertThat(res("nsg-rule-ssh").get("type")).isEqualTo("azure-native:network:SecurityRule");
        assertThat(res("ip-master").get("type")).isEqualTo("azure-native:network:PublicIPAddress");
        assertThat(res("nic-master").get("type")).isEqualTo("azure-native:network:NetworkInterface");
        assertThat(res("master").get("type")).isEqualTo("azure-native:compute:VirtualMachine");
    }

    @Test
    @SuppressWarnings("unchecked")
    void customDataIsBase64Encoded() {
        // Azure 는 평문 customData 를 그대로 무시한다. cloud-init 이 아예 돌지 않는다.
        Map<String, Object> osProfile = (Map<String, Object>) props("master").get("osProfile");

        assertThat((Map<String, Object>) osProfile.get("customData")).containsKey("fn::toBase64");
    }

    @Test
    void publicIpIsStatic() {
        // Dynamic 이면 VM 이 뜬 뒤에야 값이 잡혀 masterPublicIp output 이 비어 나온다.
        assertThat(props("ip-master")).containsEntry("publicIPAllocationMethod", "Static");
    }

    @Test
    void intraVnetRuleAllowsAnyProtocol() {
        // Azure NSG 는 IP protocol 4 를 지정할 방법이 없어 Calico IPIP 가 * 규칙에 의존한다.
        assertThat(props("nsg-rule-intra-vnet"))
                .containsEntry("protocol", "*")
                .containsEntry("sourceAddressPrefix", spec(cfg()).vpcCidr());
    }

    @Test
    void securityRulePrioritiesAreUnique() {
        // 우선순위가 겹치면 ARM 이 배포를 거부한다.
        List<Object> priorities = List.of(
                props("nsg-rule-ssh").get("priority"),
                props("nsg-rule-k8s-api").get("priority"),
                props("nsg-rule-nodeport").get("priority"),
                props("nsg-rule-intra-vnet").get("priority"));

        assertThat(priorities).doesNotHaveDuplicates();
    }

    @Test
    @SuppressWarnings("unchecked")
    void defaultImageUsesFourPartCoordinate() {
        // Azure 는 이미지를 단일 ID 로 지정하지 않는다.
        Map<String, Object> storage = (Map<String, Object>) props("master").get("storageProfile");

        assertThat((Map<String, Object>) storage.get("imageReference"))
                .containsEntry("publisher", "Canonical")
                .containsEntry("offer", "ubuntu-24_04-lts")
                .containsEntry("sku", "server")
                .containsEntry("version", "latest");
    }

    @Test
    void malformedOsImageFailsBeforePreview() {
        Map<String, String> cfg = cfg();
        cfg.put("osImage", "ocid1.image.oc1..demo");

        assertThatThrownBy(() -> new AzureYamlEmitter().emit(PulumiProgram.builder("x"), spec(cfg)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publisher:offer:sku:version");
    }

    @Test
    void resourceGroupDefaultsFromClusterName() {
        Map<String, String> cfg = cfg();
        cfg.remove("providerSpec.resourceGroup");

        assertThat(props("rg")).containsKey("resourceGroupName");
        assertThat(spec(cfg).providerSpec()).hasToString("Azure[resourceGroup=demo-rg]");
    }

    @Test
    void defaultsSkippedMeansGuardFires() {
        // Defaults 를 거치지 않은 raw spec 은 emitter 가 직접 막아야 한다.
        Map<String, String> cfg = cfg();
        cfg.remove("providerSpec.resourceGroup");

        assertThatThrownBy(() -> new AzureYamlEmitter().emit(PulumiProgram.builder("x"), ClusterSpec.from(cfg)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resourceGroup");
    }

    @Test
    @SuppressWarnings("unchecked")
    void privateIpReadsFromNicNotVm() {
        // Azure 는 private IP 를 VM 이 아니라 NIC 의 ipConfiguration 에 붙인다.
        Map<String, Object> outputs = (Map<String, Object>) doc(cfg()).get("outputs");

        assertThat(outputs.get("masterPrivateIp")).isEqualTo("${nic-master.ipConfigurations[0].privateIPAddress}");
    }
}
