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

class OpenstackYamlEmitterTest {

    private final OpenstackYamlEmitter emitter = new OpenstackYamlEmitter();

    private ClusterSpec spec(int workerCount) {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("provider", "openstack");
        cfg.put("name", "demo");
        cfg.put("environment", "dev");
        cfg.put("region", "RegionOne");
        cfg.put("masterCount", "1");
        cfg.put("workerCount", String.valueOf(workerCount));
        cfg.put("openstackExternalNetworkId", "ext-net-id");
        cfg.put("openstackFloatingIpPool", "public");
        cfg.put("joinToken", "abcdef.0123456789abcdef");
        return Defaults.applyProviderDefaults(ClusterSpec.from(cfg));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resourcesOf(ClusterSpec spec) {
        PulumiProgram.Builder b = PulumiProgram.builder("anycloud-k8s");
        emitter.emit(b, spec);
        Map<String, Object> doc = new Yaml().load(b.build().toYaml());
        return (Map<String, Object>) doc.get("resources");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> propsOf(Map<String, Object> resources, String name) {
        return (Map<String, Object>) ((Map<String, Object>) resources.get(name)).get("properties");
    }

    @SuppressWarnings("unchecked")
    private String typeOf(Map<String, Object> resources, String name) {
        return (String) ((Map<String, Object>) resources.get(name)).get("type");
    }

    @Test
    void nameIsCanonicalProviderToken() {
        assertThat(emitter.name()).isEqualTo("openstack");
    }

    @Test
    void emitsNetworkStack() {
        assertThat(resourcesOf(spec(1))).containsKeys("net", "subnet", "router", "routerIface", "secgroup");
    }

    @Test
    void typeTokensMatchProviderSchema() {
        // 스키마에서 확인한 값이다. 틀리면 preview 에서 unknown resource type 으로 드러난다.
        Map<String, Object> res = resourcesOf(spec(1));

        assertThat(typeOf(res, "net")).isEqualTo("openstack:networking/network:Network");
        assertThat(typeOf(res, "subnet")).isEqualTo("openstack:networking/subnet:Subnet");
        assertThat(typeOf(res, "router")).isEqualTo("openstack:networking/router:Router");
        assertThat(typeOf(res, "routerIface")).isEqualTo("openstack:networking/routerInterface:RouterInterface");
        assertThat(typeOf(res, "secgroup")).isEqualTo("openstack:networking/secGroup:SecGroup");
        assertThat(typeOf(res, "master")).isEqualTo("openstack:compute/instance:Instance");
        assertThat(typeOf(res, "keypair")).isEqualTo("openstack:compute/keypair:Keypair");
        assertThat(typeOf(res, "sshKey")).isEqualTo("tls:index/privateKey:PrivateKey");
        assertThat(typeOf(res, "fip-master")).isEqualTo("openstack:networking/floatingIp:FloatingIp");
        assertThat(typeOf(res, "fipassoc-master"))
                .isEqualTo("openstack:networking/floatingIpAssociate:FloatingIpAssociate");
    }

    @Test
    void emitsOneInstancePerNode() {
        Map<String, Object> res = resourcesOf(spec(3));

        assertThat(res).containsKeys("master", "worker-1", "worker-2", "worker-3");
        assertThat(res).doesNotContainKey("worker-4");
    }

    @Test
    void everyNodeGetsPortFloatingIpAndAssociation() {
        Map<String, Object> res = resourcesOf(spec(2));

        for (String node : List.of("master", "worker-1", "worker-2")) {
            assertThat(res).containsKeys("port-" + node, "fip-" + node, "fipassoc-" + node);
        }
    }

    @Test
    void floatingIpAssociatesWithPortNotInstance() {
        // 스키마의 FloatingIpAssociate 는 portId 만 받는다. instanceId 는 존재하지 않는 속성이다.
        Map<String, Object> props = propsOf(resourcesOf(spec(1)), "fipassoc-master");

        assertThat(props).containsKey("portId");
        assertThat(props).doesNotContainKey("instanceId");
        assertThat(props.get("portId")).isEqualTo("${port-master.id}");
    }

    @Test
    void masterUserDataIsEmbedded() {
        // kubeadm 스크립트가 인스턴스에 들어가지 않으면 클러스터가 만들어지지 않는다.
        Map<String, Object> props = propsOf(resourcesOf(spec(1)), "master");

        assertThat((String) props.get("userData")).contains("kubeadm").contains("#!/bin/bash");
    }

    @Test
    void securityGroupRulesCoverRequiredPorts() {
        Map<String, Object> res = resourcesOf(spec(1));
        String yaml = new Yaml().dump(res);

        // SSH, kube-apiserver, NodePort 범위가 없으면 클러스터에 접근할 수 없다.
        assertThat(yaml).contains("22").contains("6443").contains("30000").contains("32767");
    }

    @Test
    void requiresExternalNetworkId() {
        // 없으면 floating IP 를 붙일 수 없다. preview 까지 가지 말고 즉시 실패해야 한다.
        Map<String, String> cfg = new HashMap<>();
        cfg.put("provider", "openstack");
        cfg.put("name", "demo");
        ClusterSpec incomplete = Defaults.applyProviderDefaults(ClusterSpec.from(cfg));

        assertThatThrownBy(() -> emitter.emit(PulumiProgram.builder("p"), incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openstackExternalNetworkId");
    }

    @Test
    void nodeRefsPointAtEmittedResources() {
        PulumiProgram.Builder b = PulumiProgram.builder("anycloud-k8s");
        StandardOutputs.NodeRefs refs = emitter.emit(b, spec(2));

        assertThat(refs.master().resource()).isEqualTo("master");
        assertThat(refs.master().publicIpResource()).isEqualTo("fip-master");
        assertThat(refs.workers()).hasSize(2);
        assertThat(refs.workers().get(0).resource()).isEqualTo("worker-1");
        assertThat(refs.vpcResource()).isEqualTo("net");
        assertThat(refs.sshKeyResource()).isEqualTo("sshKey");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dependsOnReferencesResourceNotProperty() {
        // ${res.id} 를 넘기면 Pulumi 가 "to must be a struct type" 으로 panic 한다.
        Map<String, Object> res = resourcesOf(spec(1));
        Map<String, Object> options = (Map<String, Object>) ((Map<String, Object>) res.get("port-master")).get("options");
        List<String> dependsOn = (List<String>) options.get("dependsOn");

        assertThat(dependsOn).containsExactly("${routerIface}");
    }
}
