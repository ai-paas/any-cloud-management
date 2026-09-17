package io.aipaas.cluster.provisioning.program;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 이미지가 갖고 오는 기본 방화벽.
 *
 * <p>OCI 의 Ubuntu 이미지는 SSH 만 허용하고 나머지를 {@code REJECT} 하는 iptables 규칙을 담고 온다.
 * kube-apiserver 는 6443 을 열지만 노드 안에서 거부되어 워커가 영영 join 하지 못한다. VCN 보안
 * 목록이 이미 경계를 정하므로 노드 안에서 한 번 더 막을 이유가 없다.
 *
 * <p>규칙이 없는 이미지에서는 아무 일도 하지 않아 다른 CSP 에도 안전하다.
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
        // 이미지 기본 방화벽은 CSP 별 분기가 아니다. 규칙이 없으면 아무 일도 하지 않는다.
        assertThat(KubeadmUserData.master(spec("aws"))).contains("iptables -C");
    }
}
