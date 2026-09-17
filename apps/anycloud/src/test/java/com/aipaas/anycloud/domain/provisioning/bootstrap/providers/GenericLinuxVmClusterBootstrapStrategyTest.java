package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenericLinuxVmClusterBootstrapStrategyTest {

    private final GenericLinuxVmClusterBootstrapStrategy strategy = new GenericLinuxVmClusterBootstrapStrategy();

    @Test
    void addonInstall_keepsCniBecauseClusterCannotFormWithoutIt() {
        String script = strategy.buildAddonInstallCommand(
                VmClusterInternalRequestSnapshot.builder().build());
        assertThat(script).contains("calico");
    }

    @Test
    void addonInstall_noLongerInstallsGpu() {
        // GPU 는 GPU_OPERATOR 컴포넌트가 소유한다. 셸에 남기면 실패가 다시 || true 로 사라진다.
        String script = strategy.buildAddonInstallCommand(VmClusterInternalRequestSnapshot.builder()
                .enableGpuOperator(true)
                .build());
        assertThat(script).doesNotContain("gpu-operator");
        assertThat(script).doesNotContain("ubuntu-drivers");
    }

    @Test
    void addonInstall_noLongerInstallsIngress() {
        String script = strategy.buildAddonInstallCommand(
                VmClusterInternalRequestSnapshot.builder().enableIngress(true).build());
        assertThat(script).doesNotContain("ingress-nginx");
    }

    @Test
    void addonInstall_hasNoFailureSwallowingOrBlockingWait() {
        String script = strategy.buildAddonInstallCommand(VmClusterInternalRequestSnapshot.builder()
                .enableGpuOperator(true)
                .enableIngress(true)
                .build());
        assertThat(script).doesNotContain("kubectl wait");
        assertThat(script).doesNotContain("|| true");
    }

    /** upstream calico.yaml 의 해당 구간을 그대로 옮긴 fixture (12칸 들여쓰기). */
    private static final String CALICO_SNIPPET = "            # no effect. This should fall within `--cluster-cidr`.\n"
            + "            # - name: CALICO_IPV4POOL_CIDR\n"
            + "            #   value: \"192.168.0.0/16\"\n"
            + "            # Disable file logging so `kubectl logs` works.\n";

    @TempDir
    Path tmp;

    /** 문자열 비교로는 sed 가 실제로 먹는지 알 수 없다. 진짜 sed 로 돌린다. */
    private String runCalicoSed(String podCidr) throws Exception {
        Path manifest = tmp.resolve("calico.yaml");
        Files.writeString(manifest, CALICO_SNIPPET, StandardCharsets.UTF_8);
        // -i 는 BSD sed 가 인자를 요구해 macOS 에서 갈린다. 표현식 검증이 목적이라 stdout 으로 받는다.
        Path out = tmp.resolve("calico-out.yaml");
        Process p = new ProcessBuilder(
                        "sh",
                        "-c",
                        "sed " + GenericLinuxVmClusterBootstrapStrategy.calicoSedArgs(podCidr) + " \""
                                + manifest.toAbsolutePath() + "\" > \"" + out.toAbsolutePath() + "\"")
                .redirectErrorStream(true)
                .start();
        assertThat(p.waitFor()).as("sed 종료 코드").isZero();
        return Files.readString(out, StandardCharsets.UTF_8);
    }

    @Test
    void calicoSed_uncommentsPoolCidrAndSubstitutesValue() throws Exception {
        String out = runCalicoSed("10.244.0.0/16");

        assertThat(out).contains("            - name: CALICO_IPV4POOL_CIDR");
        assertThat(out).contains("              value: \"10.244.0.0/16\"");
        assertThat(out).doesNotContain("192.168.0.0/16");
    }

    @Test
    void calicoSed_keepsYamlIndentationAligned() throws Exception {
        // value 는 `- name` 의 name 과 같은 열이어야 한다. 어긋나면 manifest 가 통째로 깨진다.
        String out = runCalicoSed("10.244.0.0/16");

        int nameCol = out.indexOf("- name: CALICO_IPV4POOL_CIDR") + 2;
        int valueCol = out.indexOf("value: \"10.244.0.0/16\"");
        int nameLineStart = out.lastIndexOf('\n', nameCol) + 1;
        int valueLineStart = out.lastIndexOf('\n', valueCol) + 1;

        assertThat(valueCol - valueLineStart).isEqualTo(nameCol - nameLineStart);
    }

    @Test
    void calicoSed_leavesSurroundingCommentsUntouched() throws Exception {
        String out = runCalicoSed("10.244.0.0/16");

        assertThat(out).contains("# no effect. This should fall within `--cluster-cidr`.");
        assertThat(out).contains("# Disable file logging so `kubectl logs` works.");
    }

    @Test
    void cniInstall_failsFastWhenSubstitutionMisses() {
        // upstream 문구가 바뀌면 치환이 조용히 빗나간다. 그대로 apply 하면 같은 장애가 재현된다.
        String script = strategy.cniInstallCommand("10.244.0.0/16");

        assertThat(script).contains("grep -q '^ *- name: CALICO_IPV4POOL_CIDR'");
        assertThat(script).contains("exit 1");
    }

    @Test
    void addonInstall_usesConfiguredPodCidr() {
        String script = strategy.buildAddonInstallCommand(VmClusterInternalRequestSnapshot.builder()
                .podCidr("10.210.0.0/16")
                .build());

        assertThat(script).contains("10.210.0.0/16");
    }

    @Test
    void addonInstall_defaultPodCidrAvoidsOnPremRfc1918() {
        // 192.168.0.0/16 은 온프레미스 OpenStack / Proxmox 망을 그대로 삼킨다.
        String script = strategy.buildAddonInstallCommand(
                VmClusterInternalRequestSnapshot.builder().build());

        assertThat(script).contains("value: \"10.244.0.0/16\"");
    }

    @Test
    void masterInit_podCidrMatchesCalicoPool() {
        // kubeadm 과 Calico 가 다른 대역을 쓰면 라우팅이 어긋난다.
        VmClusterInternalRequestSnapshot snapshot = VmClusterInternalRequestSnapshot.builder()
                .providerConfig(java.util.Map.of("anycloud-k8s:joinToken", "abcdef.0123456789abcdef"))
                .build();

        assertThat(strategy.initializeMasterCommand(snapshot)).contains("--pod-network-cidr='10.244.0.0/16'");
        assertThat(strategy.buildAddonInstallCommand(snapshot)).contains("value: \"10.244.0.0/16\"");
    }
}
