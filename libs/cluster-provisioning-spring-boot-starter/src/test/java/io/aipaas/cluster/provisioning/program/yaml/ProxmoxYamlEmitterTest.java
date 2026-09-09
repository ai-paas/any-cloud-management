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

/** Proxmox YAML 프로그램 회귀 보호. */
class ProxmoxYamlEmitterTest {

    private Map<String, String> cfg() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("provider", "proxmox");
        cfg.put("name", "demo");
        cfg.put("workerCount", "1");
        cfg.put("joinToken", "abcdef.0123456789abcdef");
        cfg.put("providerSpec.nodeName", "pve1");
        return cfg;
    }

    private ClusterSpec spec(Map<String, String> cfg) {
        return Defaults.applyProviderDefaults(ClusterSpec.from(cfg));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doc(Map<String, String> cfg) {
        PulumiProgram.Builder b = PulumiProgram.builder("anycloud-k8s");
        StandardOutputs.NodeRefs refs = new ProxmoxYamlEmitter().emit(b, spec(cfg));
        StandardOutputs.apply(b, spec(cfg), refs);
        return (Map<String, Object>) new Yaml().load(b.build().toYaml());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> props(String name) {
        Map<String, Object> resources = (Map<String, Object>) doc(cfg()).get("resources");
        return (Map<String, Object>) ((Map<String, Object>) resources.get(name)).get("properties");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> res(String name) {
        return (Map<String, Object>) ((Map<String, Object>) doc(cfg()).get("resources")).get(name);
    }

    @Test
    @SuppressWarnings("unchecked")
    void emitsImageSnippetAndNodes() {
        Map<String, Object> resources = (Map<String, Object>) doc(cfg()).get("resources");

        assertThat(resources)
                .containsKeys("sshKey", "image", "cloudinit-master", "master", "cloudinit-worker-1", "worker-1");
    }

    @Test
    void usesSchemaVerifiedTypeTokens() {
        assertThat(res("image").get("type")).isEqualTo("proxmoxve:download/file:File");
        assertThat(res("cloudinit-master").get("type")).isEqualTo("proxmoxve:index/fileLegacy:FileLegacy");
        assertThat(res("master").get("type")).isEqualTo("proxmoxve:index/vmLegacy:VmLegacy");
    }

    @Test
    void createsNoNetworkResources() {
        // Proxmox 는 하이퍼바이저다. VPC, 서브넷, 보안그룹을 만들지 않고 기존 브리지에 붙인다.
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) doc(cfg()).get("resources");

        assertThat(resources).doesNotContainKeys("vpc", "subnet", "secgroup", "router", "nsg");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rootDiskDeclaresInterface() {
        // interface 는 필수다. 빠지면 preview 가 타입 불일치로 죽는다.
        List<Map<String, Object>> disks =
                (List<Map<String, Object>>) props("master").get("disks");

        assertThat(disks).hasSize(1);
        assertThat(disks.get(0)).containsEntry("interface", "scsi0").containsKey("size");
    }

    @Test
    @SuppressWarnings("unchecked")
    void cloudInitUsesSeparateInterface() {
        // 루트 디스크와 같은 인터페이스를 쓰면 하나가 덮인다.
        Map<String, Object> init = (Map<String, Object>) props("master").get("initialization");

        assertThat(init).containsEntry("interface", "ide2");
        assertThat(init.get("userDataFileId")).isEqualTo("${cloudinit-master.id}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void userDataGoesThroughSnippetFile() {
        // VM 속성에 user-data 를 직접 넣는 자리가 없다.
        Map<String, Object> raw =
                (Map<String, Object>) props("cloudinit-master").get("sourceRaw");

        assertThat(raw).containsKey("data").containsKey("fileName");
        assertThat(props("cloudinit-master")).containsEntry("contentType", "snippets");
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectsSshPublicKey() {
        // user-data 스니펫에는 키가 없다. 빠지면 VM 은 뜨고 bootstrap 의 SSH 접속만 실패한다.
        Map<String, Object> init = (Map<String, Object>) props("master").get("initialization");
        Map<String, Object> account = (Map<String, Object>) init.get("userAccount");

        assertThat((List<String>) account.get("keys")).containsExactly("${sshKey.publicKeyOpenssh}");
        assertThat(account).containsEntry("username", "ubuntu");
    }

    @Test
    void snippetUploadDefaultsToSftp() {
        // stream 은 sudo 를 쓴다. sftp 여야 권한 없는 SSH 계정으로 운영할 수 있다.
        assertThat(props("cloudinit-master")).containsEntry("uploadMode", "sftp");
    }

    @Test
    void snippetUploadModeIsOverridable() {
        // SFTP subsystem 이 꺼진 호스트가 있다.
        Map<String, String> cfg = cfg();
        cfg.put("providerSpec.snippetUploadMode", "stream");

        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) doc(cfg).get("resources");
        @SuppressWarnings("unchecked")
        Map<String, Object> snippet =
                (Map<String, Object>) ((Map<String, Object>) resources.get("cloudinit-master")).get("properties");

        assertThat(snippet).containsEntry("uploadMode", "stream");
    }

    @Test
    void qemuAgentIsEnabled() {
        // 꺼져 있으면 ipv4Addresses 가 빈 배열이라 masterPublicIp 가 비어 나온다.
        assertThat(props("master")).containsEntry("agent", Map.of("enabled", true));
    }

    @Test
    void instanceTypeSplitsIntoCoresAndMemory() {
        Map<String, String> cfg = cfg();
        cfg.put("masterInstanceType", "8-16384");

        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) doc(cfg).get("resources");
        @SuppressWarnings("unchecked")
        Map<String, Object> master =
                (Map<String, Object>) ((Map<String, Object>) resources.get("master")).get("properties");

        assertThat((Map<String, Object>) master.get("cpu")).containsEntry("cores", 8);
        assertThat((Map<String, Object>) master.get("memory")).containsEntry("dedicated", 16384);
    }

    @Test
    void malformedInstanceTypeFallsBackToDefaults() {
        // 값이 깨졌다고 프로비저닝을 죽이면 Defaults 가 손쓸 기회가 없다.
        Map<String, String> cfg = cfg();
        cfg.put("masterInstanceType", "large");

        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) doc(cfg).get("resources");
        @SuppressWarnings("unchecked")
        Map<String, Object> master =
                (Map<String, Object>) ((Map<String, Object>) resources.get("master")).get("properties");

        assertThat((Map<String, Object>) master.get("cpu")).containsEntry("cores", 2);
    }

    @Test
    void nodeNameIsRequired() {
        // Proxmox 는 클러스터라도 어느 노드에 올릴지 지정해야 한다.
        Map<String, String> cfg = cfg();
        cfg.remove("providerSpec.nodeName");

        assertThatThrownBy(() -> new ProxmoxYamlEmitter().emit(PulumiProgram.builder("x"), spec(cfg)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nodeName");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nodeIpComesFromQemuAgent() {
        Map<String, Object> outputs = (Map<String, Object>) doc(cfg()).get("outputs");

        assertThat(outputs.get("masterPrivateIp")).isEqualTo("${master.ipv4Addresses[0][0]}");
    }
}
