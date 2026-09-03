package io.aipaas.cluster.provisioning.program.provisioner;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pulumi.test.PulumiTest;
import com.pulumi.test.TestResult;
import io.aipaas.cluster.provisioning.program.ClusterSpec;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 7 CSP provisioner 의 wiring smoke test. {@link SmokeMocks} 가 모든 CSP API 호출/자원 생성을 mock,
 * 실제 클라우드 호출 없이 program 의 의존 그래프 + 표준 output 키 노출만 검증.
 *
 * <p>본 테스트는 다음을 보장:
 *
 * <ul>
 *   <li>provisioner 가 어떤 RuntimeException 도 던지지 않고 끝까지 실행됨
 *   <li>표준 output 키 (provider/clusterName/masterPublicIp/apiServerUrl/nodes 등) 가 모두 export 됨
 *   <li>exit code 0 (Pulumi engine 이 program 실행 성공으로 판정)
 * </ul>
 *
 * <p>참고: 본 테스트는 CSP API 호출이 정확한지는 검증 X (mock 응답을 그대로 받아들임). 실제 cluster
 * 생성 가능성 확인은 dev compose 에서 별도 e2e smoke test 가 담당.
 */
class ProvisionerSmokeTest {

    /** 표준 schema (모든 CSP 공통) — provisioner 가 반드시 export 해야 하는 output 키. */
    private static final Set<String> REQUIRED_OUTPUT_KEYS = Set.of(
            "provider",
            "clusterName",
            "masterVmSpec",
            "workerVmSpec",
            "osImage",
            "masterInstanceId",
            "masterPublicIp",
            "masterPrivateIp",
            "apiServerUrl",
            "sshPrivateKeyPem",
            "kubeconfigRemotePath",
            "kubeconfigFetchCommand",
            "nodes");

    @AfterEach
    void cleanup() {
        // PulumiTest 가 ThreadLocal 로 mock state 유지 — 테스트 격리 위해 매 테스트 후 정리.
        PulumiTest.cleanup();
    }

    @Test
    @DisplayName("AWS provisioner wires VPC + EC2 + outputs")
    void awsProvisionerWiresEndToEnd() {
        runSmokeFor(new AwsProvisioner(), SmokeSpecs.base("aws"));
    }

    @Test
    @DisplayName("GCP provisioner wires VPC + Compute + outputs")
    void gcpProvisionerWiresEndToEnd() {
        runSmokeFor(new GcpProvisioner(), SmokeSpecs.base("gcp"));
    }

    @Test
    @DisplayName("Azure provisioner wires RG + VM + outputs (azure-native)")
    void azureProvisionerWiresEndToEnd() {
        runSmokeFor(new AzureProvisioner(), SmokeSpecs.base("azure"));
    }

    @Test
    @DisplayName("OCI provisioner wires VCN + Compute + outputs")
    void ociProvisionerWiresEndToEnd() {
        runSmokeFor(new OciProvisioner(), SmokeSpecs.base("oci"));
    }

    @Test
    @DisplayName("Alibaba provisioner wires VPC + ECS + outputs")
    void alibabaProvisionerWiresEndToEnd() {
        runSmokeFor(new AlibabaProvisioner(), SmokeSpecs.base("alibaba"));
    }

    @Test
    @DisplayName("DigitalOcean provisioner wires VPC + Droplet + Firewall + outputs")
    void digitalOceanProvisionerWiresEndToEnd() {
        runSmokeFor(new DigitalOceanProvisioner(), SmokeSpecs.base("digitalocean"));
    }

    @Test
    @DisplayName("OpenStack provisioner wires Network + Compute + FloatingIp + outputs")
    void openstackProvisionerWiresEndToEnd() {
        runSmokeFor(new OpenstackProvisioner(), SmokeSpecs.base("openstack"));
    }

    private static void runSmokeFor(ProviderProvisioner provisioner, ClusterSpec spec) {
        TestResult result = PulumiTest.withMocks(new SmokeMocks())
                .runTest(ctx -> provisioner.provision(ctx, spec).forEach(ctx::export));

        // exit code 0 == success (Pulumi engine 이 program 실행 성공으로 판정).
        assertEquals(
                0,
                result.exitCode(),
                () -> "provisioner=" + provisioner.name()
                        + " exit code != 0\nerrors=" + result.errors()
                        + "\nexceptions=" + summarize(result.exceptions()));

        // exception 0건.
        assertTrue(
                result.exceptions().isEmpty(),
                () -> "provisioner=" + provisioner.name()
                        + " threw " + result.exceptions().size() + " exception(s): "
                        + summarize(result.exceptions()));

        // 표준 output 키 누락 없음.
        Set<String> exported = result.outputs().keySet();
        for (String key : REQUIRED_OUTPUT_KEYS) {
            assertTrue(
                    exported.contains(key),
                    () -> "provisioner=" + provisioner.name() + " missing output key '" + key
                            + "'. Exported: " + exported);
        }

        // nodes output non-null + 1 master + 2 worker.
        assertNotNull(result.outputs().get("nodes"), () -> provisioner.name() + " nodes output null");
    }

    private static void assertEquals(int expected, int actual, java.util.function.Supplier<String> msg) {
        if (expected != actual) {
            throw new AssertionError(msg.get());
        }
    }

    private static String summarize(List<? extends Throwable> errors) {
        if (errors.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (Throwable t : errors) {
            sb.append("\n  - ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        }
        return sb.toString();
    }
}
