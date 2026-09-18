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
    void emitsImageAndNodesOnly() {
        Map<String, Object> resources = (Map<String, Object>) doc(cfg()).get("resources");

        assertThat(resources).containsKeys("sshKey", "image", "master", "worker-1");
    }

    @Test
    void usesSchemaVerifiedTypeTokens() {
        assertThat(res("image").get("type")).isEqualTo("proxmoxve:download/file:File");
        assertThat(res("master").get("type")).isEqualTo("proxmoxve:index/vmLegacy:VmLegacy");
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsNoSnippetFile() {
        /*
         * Proxmox API 는 업로드 content type 으로 snippets 를 받지 않는다(pve-devel #2208). 다시 넣으면
         * provider 가 PVE 호스트로 SSH 하게 되고, 자격증명이 하이퍼바이저 접근 권한까지 요구한다.
         */
        Map<String, Object> resources = (Map<String, Object>) doc(cfg()).get("resources");

        assertThat(resources).doesNotContainKeys("cloudinit-master", "cloudinit-worker-1");
        assertThat(resources.values())
                .noneMatch(r -> ((Map<String, Object>) r).get("type").toString().contains("fileLegacy"));
    }

    @Test
    void theImageGoesToTheDirectoryDatastore() {
        // 블록 스토리지(local-lvm)는 import content 를 받지 않아 다운로드가 실패한다.
        assertThat(props("image")).containsEntry("datastoreId", "local").containsEntry("contentType", "import");
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

        assertThat(init).containsEntry("interface", "ide2").doesNotContainKey("userDataFileId");
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectsSshPublicKey() {
        // 노드에 들어가는 유일한 경로다. 빠지면 VM 은 뜨지만 부트스트랩이 붙지 못한다.
        Map<String, Object> init = (Map<String, Object>) props("master").get("initialization");
        Map<String, Object> account = (Map<String, Object>) init.get("userAccount");

        assertThat((List<String>) account.get("keys")).containsExactly("${sshKey.publicKeyOpenssh}");
        assertThat(account).containsEntry("username", "ubuntu");
    }

    @Test
    void qemuAgentIsEnabledWhenTheAddressMustBeDiscovered() {
        // 꺼져 있으면 ipv4Addresses 가 빈 배열이라 masterPublicIp 가 비어 나온다.
        assertThat(props("master")).containsEntry("agent", Map.of("enabled", true));
    }

    @Test
    void staticAddressingTurnsTheAgentWaitOff() {
        /*
         * provider 는 agent 가 켜져 있으면 IP 보고를 받을 때까지 VM 생성을 끝내지 않는다. cloud
         * 이미지에 qemu-guest-agent 가 없어 그 보고가 오지 않고, 생성이 무한정 멈춘다.
         */
        assertThat(staticProps("master")).containsEntry("agent", Map.of("enabled", false));
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

    @Test
    @SuppressWarnings("unchecked")
    void everyProxmoxResourceDeclaresWhereToGetThePlugin() {
        // 플러그인 캐시는 볼륨이라 이미지에 구운 것이 남지 않는다. 선언이 빠지면 매번 403 이다.
        Map<String, Object> resources = (Map<String, Object>) doc(cfg()).get("resources");

        assertThat(resources.values())
                .filteredOn(
                        r -> ((Map<String, Object>) r).get("type").toString().startsWith("proxmoxve:"))
                .isNotEmpty()
                .allSatisfy(r -> {
                    Map<String, Object> options = (Map<String, Object>) ((Map<String, Object>) r).get("options");
                    assertThat(options)
                            .containsEntry("pluginDownloadURL", "github://api.github.com/muhlba91/pulumi-proxmoxve")
                            .containsKey("version");
                });
    }

    @Test
    void theDownloadedImageGetsAnExtensionPveAccepts() {
        /*
         * import content 가 받는 확장자는 .ova .ovf .qcow2 .raw .vmdk 뿐이다. Ubuntu cloud 이미지는
         * .img 로 배포되지만 내용은 qcow2 라, 이름을 그대로 쓰면 400 invalid filename 이 난다.
         */
        assertThat(props("image").get("fileName")).isEqualTo("demo-base.qcow2");
    }

    @Test
    void anExplicitImageKeepsItsOwnExtension() {
        Map<String, String> cfg = cfg();
        cfg.put("osImage", "https://mirror.internal/images/noble.raw");

        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) doc(cfg).get("resources");
        @SuppressWarnings("unchecked")
        Map<String, Object> image =
                (Map<String, Object>) ((Map<String, Object>) resources.get("image")).get("properties");

        assertThat(image.get("fileName")).isEqualTo("demo-base.raw");
    }

    @Test
    void noCdromDriveIsAttached() {
        /*
         * fileId 를 비우면 provider 가 기본값 cdrom 을 보내고, PVE 는 빈 CD-ROM 드라이브 연결에
         * / 의 Sys.Console 을 요구한다. VM 생성이 403 으로 거절된다.
         */
        assertThat(props("master")).containsEntry("cdrom", Map.of("fileId", "none"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theNetworkDeviceOmitsTheDeprecatedEnabledFlag() {
        List<Map<String, Object>> devices =
                (List<Map<String, Object>>) props("master").get("networkDevices");

        assertThat(devices).hasSize(1);
        assertThat(devices.get(0)).doesNotContainKey("enabled").containsEntry("bridge", "vmbr0");
    }

    private Map<String, String> staticCfg() {
        Map<String, String> cfg = cfg();
        cfg.put("providerSpec.nodeIps", "192.168.0.200,192.168.0.201");
        cfg.put("providerSpec.gateway", "192.168.0.1");
        cfg.put("providerSpec.sshHost", "220.78.15.185");
        cfg.put("providerSpec.sshPorts", "2200,2201");
        return cfg;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> staticProps(String name) {
        Map<String, Object> resources = (Map<String, Object>) doc(staticCfg()).get("resources");
        return (Map<String, Object>) ((Map<String, Object>) resources.get(name)).get("properties");
    }

    @Test
    @SuppressWarnings("unchecked")
    void staticAddressesAreWrittenIntoCloudInit() {
        /*
         * cloud 이미지에 qemu-guest-agent 가 없어 DHCP 로 받은 주소를 알아낼 방법이 없다. 주소를
         * 지정하지 않으면 Pulumi 가 ipv4Addresses 를 기다리며 멈춘다.
         */
        Map<String, Object> init = (Map<String, Object>) staticProps("master").get("initialization");
        List<Map<String, Object>> ipConfigs = (List<Map<String, Object>>) init.get("ipConfigs");
        Map<String, Object> ipv4 = (Map<String, Object>) ipConfigs.get(0).get("ipv4");

        assertThat(ipv4).containsEntry("address", "192.168.0.200/24").containsEntry("gateway", "192.168.0.1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachNodeGetsItsOwnAddressInOrder() {
        // master 가 0, worker 가 1 부터다. 순서가 어긋나면 노드마다 남의 주소가 붙는다.
        Map<String, Object> init = (Map<String, Object>) staticProps("worker-1").get("initialization");
        List<Map<String, Object>> ipConfigs = (List<Map<String, Object>>) init.get("ipConfigs");

        assertThat((Map<String, Object>) ipConfigs.get(0).get("ipv4")).containsEntry("address", "192.168.0.201/24");
    }

    @Test
    @SuppressWarnings("unchecked")
    void staticNodesGetResolversBecauseDhcpNoLongerProvidesThem() {
        // 없으면 노드가 apt 와 컨테이너 레지스트리를 찾지 못해 부트스트랩이 멈춘다.
        Map<String, Object> init = (Map<String, Object>) staticProps("master").get("initialization");
        Map<String, Object> dns = (Map<String, Object>) init.get("dns");

        assertThat((List<String>) dns.get("servers")).containsExactly("1.1.1.1", "8.8.8.8");
    }

    @Test
    @SuppressWarnings("unchecked")
    void outputsCarryTheStaticAddressInsteadOfAskingTheAgent() {
        Map<String, Object> outputs = (Map<String, Object>) doc(staticCfg()).get("outputs");

        assertThat(outputs.get("masterPrivateIp")).isEqualTo("192.168.0.200");
        assertThat(outputs.get("masterPublicIp")).isEqualTo("220.78.15.185");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theForwardedPortTravelsWithEachNode() {
        // 공유기가 노드마다 다른 포트를 연다. 값이 없으면 부트스트랩이 22 로 붙어 실패한다.
        Map<String, Object> outputs = (Map<String, Object>) doc(staticCfg()).get("outputs");

        assertThat(new Yaml().dump(outputs.get("nodes")))
                .contains("sshPort: 2200")
                .contains("sshPort: 2201");
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutStaticAddressesTheAgentIsStillTheSource() {
        // 기존 동작을 바꾸지 않는다. 주소를 지정하지 않으면 예전대로 agent 에게 묻는다.
        Map<String, Object> outputs = (Map<String, Object>) doc(cfg()).get("outputs");

        assertThat(outputs.get("masterPrivateIp")).isEqualTo("${master.ipv4Addresses[0][0]}");
    }
}
