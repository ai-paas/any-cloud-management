package io.aipaas.cluster.provisioning.program.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aipaas.cluster.provisioning.program.ClusterSpec;
import io.aipaas.cluster.provisioning.program.Defaults;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Alibaba YAML 프로그램 회귀 보호. */
class AlibabaYamlEmitterTest {

    private Map<String, String> cfg() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("provider", "alibaba");
        cfg.put("name", "demo");
        cfg.put("workerCount", "1");
        cfg.put("joinToken", "abcdef.0123456789abcdef");
        cfg.put("providerSpec.zone", "ap-northeast-2a");
        cfg.put("osImage", "ubuntu_24_04_x64_20G_alibase_20260828.vhd");
        return cfg;
    }

    private ClusterSpec spec(Map<String, String> cfg) {
        return Defaults.applyProviderDefaults(ClusterSpec.from(cfg));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doc(Map<String, String> cfg) {
        PulumiProgram.Builder b = PulumiProgram.builder("anycloud-k8s");
        StandardOutputs.NodeRefs refs = new AlibabaYamlEmitter().emit(b, spec(cfg));
        StandardOutputs.apply(b, spec(cfg), refs);
        return (Map<String, Object>) new Yaml().load(b.build().toYaml());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resources(Map<String, String> cfg) {
        return (Map<String, Object>) doc(cfg).get("resources");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> props(String name) {
        return (Map<String, Object>) ((Map<String, Object>) resources(cfg()).get(name)).get("properties");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> res(String name) {
        return (Map<String, Object>) resources(cfg()).get(name);
    }

    @Test
    void emitsNetworkSecurityAndNodes() {
        assertThat(resources(cfg()))
                .containsKeys("sshKeyPair", "sshKey", "vpc", "subnet", "secgroup", "master", "worker-1");
    }

    @Test
    void usesSchemaVerifiedTypeTokens() {
        // 토큰이 틀리면 preview 가 "unknown resource type" 으로 죽는다.
        assertThat(res("vpc").get("type")).isEqualTo("alicloud:vpc/network:Network");
        assertThat(res("subnet").get("type")).isEqualTo("alicloud:vpc/switch:Switch");
        assertThat(res("secgroup").get("type")).isEqualTo("alicloud:ecs/securityGroup:SecurityGroup");
        assertThat(res("master").get("type")).isEqualTo("alicloud:ecs/instance:Instance");
        assertThat(res("master-eip").get("type")).isEqualTo("alicloud:ecs/eipAddress:EipAddress");
    }

    @Test
    void theSwitchCarriesTheZone() {
        // VSwitch 는 zone 단위다. 빠지면 서브넷을 만들지 못한다.
        assertThat(props("subnet")).containsEntry("zoneId", "ap-northeast-2a");
    }

    @Test
    void zoneIsRequired() {
        /*
         * 계정과 인스턴스 타입마다 쓸 수 있는 zone 이 달라 리전에서 유도하지 않는다. 자동으로
         * 고르면 재고 없음이 엉뚱한 오류로 나온다.
         */
        Map<String, String> cfg = cfg();
        cfg.remove("providerSpec.zone");

        assertThatThrownBy(() -> new AlibabaYamlEmitter().emit(PulumiProgram.builder("x"), spec(cfg)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zone");
    }

    @Test
    void everyNodeGetsItsOwnElasticIp() {
        // EIP 는 인스턴스와 따로 산다. 스택이 함께 관리해야 destroy 에서 같이 사라진다.
        assertThat(resources(cfg())).containsKeys("master-eip", "master-eip-assoc", "worker-1-eip");
    }

    @Test
    void thePublicAddressComesFromTheElasticIp() {
        // EIP 를 붙이면 인스턴스의 publicIp 는 비어 있다. 거기를 가리키면 출력이 빈 값이 된다.
        @SuppressWarnings("unchecked")
        Map<String, Object> outputs = (Map<String, Object>) doc(cfg()).get("outputs");

        assertThat(outputs.get("masterPublicIp")).isEqualTo("${master-eip.ipAddress}");
        assertThat(outputs.get("masterPrivateIp")).isEqualTo("${master.privateIp}");
    }

    @Test
    void nodeTrafficIsOpenedWithoutCountingPorts() {
        /*
         * kubelet, etcd, Calico 가 TCP 와 UDP 를 모두 쓴다. 포트를 하나씩 세면 새 구성 요소가
         * 붙을 때마다 조용히 막힌다.
         */
        @SuppressWarnings("unchecked")
        Map<String, Object> intra =
                (Map<String, Object>) ((Map<String, Object>) resources(cfg()).get("sg-intra")).get("properties");

        assertThat(intra).containsEntry("ipProtocol", "all").containsEntry("portRange", "-1/-1");
        assertThat(intra.get("cidrIp")).isEqualTo(spec(cfg()).vpcCidr());
    }

    @Test
    void portRangesUseTheSlashForm() {
        // 숫자 하나만 주면 provider 가 거절한다.
        assertThat(props("sg-ssh")).containsEntry("portRange", "22/22");
        assertThat(props("sg-api")).containsEntry("portRange", "6443/6443");
    }

    @Test
    void theSystemDiskNeverGoesBelowTheMinimum() {
        // ECS 시스템 디스크 최소가 20GB 다. 그보다 작게 요청하면 생성이 거절된다.
        Map<String, String> cfg = cfg();
        cfg.put("rootDiskSizeGb", "5");

        @SuppressWarnings("unchecked")
        Map<String, Object> master =
                (Map<String, Object>) ((Map<String, Object>) resources(cfg).get("master")).get("properties");

        assertThat(master.get("systemDiskSize")).isEqualTo(20);
    }

    @Test
    void masterAndWorkerCarryTheirOwnUserData() {
        assertThat((String) props("master").get("userData")).contains("kubeadm");
        @SuppressWarnings("unchecked")
        Map<String, Object> worker =
                (Map<String, Object>) ((Map<String, Object>) resources(cfg()).get("worker-1")).get("properties");
        assertThat((String) worker.get("userData")).contains("kubeadm");
    }

    @Test
    void theKeyPairIsWiredByName() {
        // ECS 는 키를 id 가 아니라 이름으로 참조한다.
        assertThat(props("master").get("keyName")).isEqualTo("${sshKey.keyPairName}");
    }

    @Test
    void pluginVersionIsPinned() {
        assertThat((Map<String, Object>) res("vpc").get("options")).containsEntry("version", "3.108.0");
    }

    @Test
    void theImageIsRequiredBecauseItsIdCarriesABuildDate() {
        /*
         * 이미지 ID 에 빌드 날짜가 붙어 주기적으로 갈리고(..._alibase_20260828.vhd) 리전마다
         * 다르다. 기본값을 박아 두면 어느 날 "이미지를 찾을 수 없음" 으로 멈춘다.
         */
        Map<String, String> cfg = cfg();
        cfg.remove("osImage");

        assertThatThrownBy(() -> new AlibabaYamlEmitter().emit(PulumiProgram.builder("x"), spec(cfg)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("osImage");
    }

    @Test
    void theRequestedImageIsUsedAsIs() {
        assertThat(props("master").get("imageId")).isEqualTo("ubuntu_24_04_x64_20G_alibase_20260828.vhd");
    }

    @Test
    void theDefaultInstanceTypeExistsInSeoul() {
        // g6 는 서울에 없다. 세대마다 제공 리전이 달라 오래된 계열은 재고 없음으로 막힌다.
        assertThat(props("master").get("instanceType")).isEqualTo("ecs.g9i.large");
    }
}
