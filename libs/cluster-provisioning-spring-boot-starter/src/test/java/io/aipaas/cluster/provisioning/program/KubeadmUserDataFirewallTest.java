package io.aipaas.cluster.provisioning.program;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 이미지가 갖고 오는 기본 방화벽.
 *
 * <p>OCI Ubuntu 이미지는 SSH 만 허용하고 나머지를 {@code REJECT} 하는 iptables 규칙을 포함.
 * kube-apiserver 가 6443 을 개방해도 노드 내부에서 거부돼 워커 join 불가. VCN 보안 목록이
 * 경계를 정의하므로 노드 내부 추가 차단 불필요.
 *
 * <p>해당 규칙이 없는 이미지에서는 무동작이므로 다른 CSP 에도 안전.
 */
class KubeadmUserDataFirewallTest {

    private ClusterSpec spec(String provider) {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("provider", provider);
        cfg.put("name", "demo");
        cfg.put("workerCount", "1");
        cfg.put("joinToken", "abcdef.0123456789abcdef");
        if ("oci".equals(provider)) {
            cfg.put("providerSpec.compartmentId", "ocid1.compartment.oc1..demo");
            cfg.put("osImage", "ocid1.image.demo");
        }
        return Defaults.applyProviderDefaults(ClusterSpec.from(cfg));
    }

    @Test
    void dropsTheImageDefaultRejectRules() {
        for (String script : new String[] {KubeadmUserData.master(spec("oci")), KubeadmUserData.worker(spec("oci"))}) {
            assertThat(script)
                    .as("기본 REJECT 를 걷어내지 않는다")
                    .contains("iptables -D")
                    .contains("icmp-host-prohibited");
            // FORWARD 를 빠뜨리면 노드는 뜨는데 파드 사이 트래픽만 막힌다.
            assertThat(script).as("INPUT 과 FORWARD 를 모두 다루지 않는다").containsPattern("for chain in INPUT FORWARD");
        }
    }

    @Test
    void checksBeforeDeleting() {
        // 규칙이 없는 이미지에서 -D 를 그냥 부르면 실패하고 set -e 가 스크립트를 중단시킨다.
        assertThat(KubeadmUserData.master(spec("oci"))).contains("iptables -C");
    }

    @Test
    void appliesToEveryProvider() {
        // 이미지 기본 방화벽은 CSP 별 분기 대상 아님. 규칙 부재 시 무동작.
        assertThat(KubeadmUserData.master(spec("aws"))).contains("iptables -C");
    }
}
