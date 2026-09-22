package com.aipaas.anycloud.domain.provisioning.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterNodeResolver;
import com.aipaas.anycloud.domain.provisioning.bootstrap.support.VmClusterNodeResolver.VmClusterNode;
import com.aipaas.anycloud.domain.provisioning.bootstrap.support.internal.VmClusterNodeResolverImpl;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * NAT 뒤 클러스터는 모든 노드가 같은 공인 주소를 쓰고 포트로만 갈린다.
 *
 * <p>host 로 노드를 찾으면 언제나 첫 번째가 걸린다. 그 상태로 부트스트랩이 돌면 worker 준비와
 * join 명령이 master 에 두 번 실행되고, worker 에는 kubeadm 조차 깔리지 않는다. 클러스터는
 * master 하나로 Ready 가 되어 성공처럼 보인다.
 */
class NodesBehindOneAddressAreToldApartByPortTest extends AbstractUnitTest {

    private final VmClusterNodeResolver resolver = new VmClusterNodeResolverImpl();

    private Map<String, Object> outputs() {
        return Map.of(
                "nodes",
                List.of(
                        Map.of("role", "master", "publicIp", "220.78.15.185", "sshPort", 2200),
                        Map.of("role", "worker", "publicIp", "220.78.15.185", "sshPort", 2201)),
                "masterPublicIp",
                "220.78.15.185");
    }

    @Test
    void everyNodeKeepsItsOwnPort() {
        assertThat(resolver.readNodes(outputs()))
                .extracting(VmClusterNode::role, VmClusterNode::port)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("master", 2200),
                        org.assertj.core.api.Assertions.tuple("worker", 2201));
    }

    @Test
    void theMasterIsPickedByRoleNotByAddress() {
        assertThat(resolver.masterNode(outputs()).port()).isEqualTo(2200);
    }

    @Test
    void aSingleMasterClusterHasNoExtraControlPlane() {
        assertThat(resolver.extraMasterNodes(outputs())).isEmpty();
    }

    @Test
    void extraControlPlaneNodesKeepTheirPorts() {
        Map<String, Object> ha = Map.of(
                "nodes",
                List.of(
                        Map.of("role", "master", "publicIp", "1.2.3.4", "sshPort", 2200),
                        Map.of("role", "master", "publicIp", "1.2.3.4", "sshPort", 2201),
                        Map.of("role", "worker", "publicIp", "1.2.3.4", "sshPort", 2202)));

        assertThat(resolver.extraMasterNodes(ha)).singleElement().satisfies(node -> assertThat(node.port())
                .isEqualTo(2201));
    }

    @Test
    void stacksWithoutAPortFallBackToTwentyTwo() {
        // 예전에 만든 클러스터에는 sshPort 가 없다. 그 클러스터도 계속 접속돼야 한다.
        Map<String, Object> legacy = Map.of(
                "nodes", List.of(Map.of("role", "master", "publicIp", "10.0.0.5")), "masterPublicIp", "10.0.0.5");

        assertThat(resolver.masterNode(legacy).port()).isEqualTo(22);
    }
}
