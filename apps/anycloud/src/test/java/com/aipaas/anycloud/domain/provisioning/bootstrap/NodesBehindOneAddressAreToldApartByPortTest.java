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
 * NAT 뒤 클러스터는 전체 노드가 동일 공인 주소를 사용하고 포트로만 구분.
 *
 * <p>host 기준 노드 조회 시 항상 첫 번째 노드 반환. 이 상태로 부트스트랩이 실행되면 worker 준비
 * 와 join 명령이 master 에 중복 실행되고 worker 에는 kubeadm 미설치. 클러스터는 master 단독으로
 * Ready 전이해 성공으로 표시.
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
        // 이전 생성 클러스터에는 sshPort 부재. 해당 클러스터도 접속 유지 필요.
        Map<String, Object> legacy = Map.of(
                "nodes", List.of(Map.of("role", "master", "publicIp", "10.0.0.5")), "masterPublicIp", "10.0.0.5");

        assertThat(resolver.masterNode(legacy).port()).isEqualTo(22);
    }
}
