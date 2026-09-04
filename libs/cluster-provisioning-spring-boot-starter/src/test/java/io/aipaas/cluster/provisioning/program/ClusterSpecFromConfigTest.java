package io.aipaas.cluster.provisioning.program;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClusterSpecFromConfigTest {

    private Map<String, String> baseConfig() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("provider", "openstack");
        cfg.put("name", "demo");
        cfg.put("environment", "dev");
        cfg.put("region", "RegionOne");
        return cfg;
    }

    @Test
    void readsScalarKeys() {
        ClusterSpec spec = ClusterSpec.from(baseConfig());

        assertThat(spec.provider()).isEqualTo("openstack");
        assertThat(spec.name()).isEqualTo("demo");
        assertThat(spec.environment()).isEqualTo("dev");
        assertThat(spec.region()).isEqualTo("RegionOne");
    }

    @Test
    void missingKeysBecomeNullNotException() {
        // 설정은 부분적으로만 채워져 오고 Defaults 가 나머지를 메운다.
        ClusterSpec spec = ClusterSpec.from(baseConfig());

        assertThat(spec.gcpProject()).isNull();
        assertThat(spec.openstackImageName()).isNull();
    }

    @Test
    void readsIntKeys() {
        Map<String, String> cfg = baseConfig();
        cfg.put("workerCount", "3");
        cfg.put("rootDiskSizeGb", "80");

        ClusterSpec spec = ClusterSpec.from(cfg);

        assertThat(spec.workerCount()).isEqualTo(3);
        assertThat(spec.rootDiskSizeGb()).isEqualTo(80);
    }

    @Test
    void malformedIntBecomesZeroNotException() {
        // 잘못된 값에 예외를 던지면 Defaults 가 손쓸 기회 없이 프로비저닝이 죽는다.
        Map<String, String> cfg = baseConfig();
        cfg.put("workerCount", "삼");

        assertThat(ClusterSpec.from(cfg).workerCount()).isZero();
    }

    @Test
    void readsBooleanKeys() {
        Map<String, String> cfg = baseConfig();
        cfg.put("useSpot", "true");

        assertThat(ClusterSpec.from(cfg).useSpot()).isTrue();
        assertThat(ClusterSpec.from(baseConfig()).useSpot()).isFalse();
    }

    @Test
    void readsCommaSeparatedList() {
        Map<String, String> cfg = baseConfig();
        cfg.put("subnetCidrs", "10.0.1.0/24,10.0.2.0/24");

        assertThat(ClusterSpec.from(cfg).subnetCidrs()).containsExactly("10.0.1.0/24", "10.0.2.0/24");
    }

    @Test
    void acceptsNamespacedKeys() {
        // 호출자가 anycloud-k8s:workerCount 형태로 넘길 수 있다 — Pulumi config 원형 그대로.
        Map<String, String> cfg = new HashMap<>();
        cfg.put("anycloud-k8s:provider", "openstack");
        cfg.put("anycloud-k8s:name", "demo");

        ClusterSpec spec = ClusterSpec.from(cfg);

        assertThat(spec.provider()).isEqualTo("openstack");
        assertThat(spec.name()).isEqualTo("demo");
    }

    @Test
    void nullConfigYieldsAllNullSpec() {
        assertThat(ClusterSpec.from(null).provider()).isNull();
    }
}
