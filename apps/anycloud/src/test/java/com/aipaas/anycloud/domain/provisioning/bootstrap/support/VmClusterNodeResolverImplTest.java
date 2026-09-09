package com.aipaas.anycloud.domain.provisioning.bootstrap.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.bootstrap.support.internal.VmClusterNodeResolverImpl;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * nodes 출력은 provisioner 에 따라 JSON 문자열로도 배열로도 온다.
 *
 * <p>Pulumi Java SDK 의 일부 역직렬화 경로가 배열을 못 다뤄 provisioner 가 JSON 문자열로 export 했는데,
 * 이 resolver 는 List 만 처리해 빈 목록을 돌려줬다. masterHost 에는 폴백이 있지만 worker join 목록에는
 * 없어 worker 가 클러스터에 붙지 않았다.
 */
class VmClusterNodeResolverImplTest {

    private final VmClusterNodeResolverImpl resolver = new VmClusterNodeResolverImpl();

    private static final String NODES_JSON = "[{\"role\":\"master\",\"instanceId\":\"i-1\",\"privateIp\":\"10.0.0.1\","
            + "\"publicIp\":\"1.2.3.4\",\"publicDns\":\"1.2.3.4\",\"ssh\":\"ssh ...\"},"
            + "{\"role\":\"worker\",\"instanceId\":\"i-2\",\"privateIp\":\"10.0.0.2\","
            + "\"publicIp\":\"1.2.3.5\",\"publicDns\":\"1.2.3.5\",\"ssh\":\"ssh ...\"}]";

    private static final List<Map<String, Object>> NODES_LIST = List.of(
            Map.of("role", "master", "publicIp", "1.2.3.4", "publicDns", "1.2.3.4"),
            Map.of("role", "worker", "publicIp", "1.2.3.5", "publicDns", "1.2.3.5"));

    @Test
    void readsJsonStringForm() {
        List<VmClusterNodeResolver.VmClusterNode> nodes = resolver.readNodes(Map.of("nodes", NODES_JSON));

        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0).role()).isEqualTo("master");
        assertThat(nodes.get(0).host()).isEqualTo("1.2.3.4");
        assertThat(nodes.get(1).role()).isEqualTo("worker");
    }

    @Test
    void readsListForm() {
        List<VmClusterNodeResolver.VmClusterNode> nodes = resolver.readNodes(Map.of("nodes", NODES_LIST));

        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(1).host()).isEqualTo("1.2.3.5");
    }

    @Test
    void workerJoinListIsNotEmptyForJsonString() {
        // 이 목록이 비면 worker 가 kubeadm join 을 하지 않는다 — 폴백이 없는 경로다.
        List<VmClusterNodeResolver.VmClusterNode> workers = resolver.readNodes(Map.of("nodes", NODES_JSON)).stream()
                .filter(node -> "worker".equalsIgnoreCase(node.role()))
                .toList();

        assertThat(workers).hasSize(1);
    }

    @Test
    void masterHostPrefersNodesOverFlatOutput() {
        Map<String, Object> outputs = Map.of("nodes", NODES_JSON, "masterPublicIp", "9.9.9.9");

        assertThat(resolver.masterHost(outputs)).isEqualTo("1.2.3.4");
    }

    @Test
    void masterHostFallsBackWhenNodesUnusable() {
        assertThat(resolver.masterHost(Map.of("nodes", "not json", "masterPublicIp", "9.9.9.9")))
                .isEqualTo("9.9.9.9");
    }

    @Test
    void extraMasterHostsWorksForJsonString() {
        // HA control plane — 폴백이 없어 JSON 문자열이면 추가 master 가 join 하지 못했다.
        String ha = "[{\"role\":\"master\",\"publicIp\":\"1.1.1.1\",\"publicDns\":\"1.1.1.1\"},"
                + "{\"role\":\"master\",\"publicIp\":\"2.2.2.2\",\"publicDns\":\"2.2.2.2\"}]";

        assertThat(resolver.extraMasterHosts(Map.of("nodes", ha))).containsExactly("2.2.2.2");
    }

    @Test
    void malformedInputYieldsEmptyNotException() {
        assertThat(resolver.readNodes(Map.of("nodes", "{broken"))).isEmpty();
        assertThat(resolver.readNodes(Map.of("nodes", 42))).isEmpty();
        assertThat(resolver.readNodes(Map.of())).isEmpty();
    }

    @Test
    void nodesWithoutHostAreDropped() {
        String noHost = "[{\"role\":\"worker\",\"privateIp\":\"10.0.0.9\"}]";

        assertThat(resolver.readNodes(Map.of("nodes", noHost))).isEmpty();
    }
}
