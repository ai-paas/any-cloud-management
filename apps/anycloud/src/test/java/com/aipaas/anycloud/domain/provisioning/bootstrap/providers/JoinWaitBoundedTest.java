package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * join 전 apiserver 대기에도 상한이 있어야 한다.
 *
 * <p>마스터가 영영 열리지 않으면 워커는 무한히 기다리고, 그동안 SSH 세션이 살아 있어 워크플로가
 * BOOTSTRAPPING 에 멈춘다. RabbitMQ delivery ack 타임아웃까지 가면 메시지가 재전달되어 같은 노드에
 * 부트스트랩이 중복으로 붙는다. 실제로 그렇게 됐다.
 */
class JoinWaitBoundedTest extends AbstractUnitTest {

    private final GenericLinuxVmClusterBootstrapStrategy strategy = new GenericLinuxVmClusterBootstrapStrategy();

    private VmClusterInternalRequestSnapshot snapshot() {
        VmClusterInternalRequestSnapshot s = new VmClusterInternalRequestSnapshot();
        s.setProviderConfig(
                new java.util.HashMap<>(java.util.Map.of("anycloud-k8s:joinToken", "abcdef.0123456789abcdef")));
        return s;
    }

    @Test
    void controlPlaneJoinWaitIsBoundedToo() {
        assertThat(strategy.buildControlPlaneJoinCommand(snapshot(), "10.0.0.5", "abc", "key"))
                .doesNotContainPattern("until nc -z [^;]+; do sleep \\d+; done");
    }

    @Test
    void workerJoinWaitIsBounded() {
        String cmd = strategy.buildWorkerJoinCommand(snapshot(), "10.0.0.5", "abc");

        assertThat(cmd).doesNotContainPattern("until nc -z [^;]+; do sleep \\d+; done");
    }

    @Test
    void joinStillWaitsForTheApiServer() {
        // 상한을 넣다가 대기를 없애면 아직 안 뜬 apiserver 로 join 이 돌아 실패한다.
        assertThat(strategy.buildWorkerJoinCommand(snapshot(), "10.0.0.5", "abc"))
                .contains("6443");
    }

    @Test
    void givingUpSaysWhy() {
        assertThat(strategy.buildWorkerJoinCommand(snapshot(), "10.0.0.5", "abc"))
                .containsIgnoringCase("timed out");
    }
}
