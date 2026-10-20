package com.aipaas.anycloud.domain.provisioning.bootstrap.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterInternalRequestSnapshot;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/**
 * join 전 apiserver 대기에도 상한 적용 필요.
 *
 * <p>마스터 기동 실패 시 워커가 무한 대기하고 그 구간에 SSH 세션이 유지돼 워크플로가
 * BOOTSTRAPPING 에 정지. RabbitMQ delivery ack 타임아웃 초과 시 메시지 재전달로 동일 노드에
 * 부트스트랩 중복 실행. 해당 사례 발생.
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
        // 상한 적용 과정에서 대기를 제거하면 미기동 apiserver 로 join 이 실행돼 실패.
        assertThat(strategy.buildWorkerJoinCommand(snapshot(), "10.0.0.5", "abc"))
                .contains("6443");
    }

    @Test
    void givingUpSaysWhy() {
        assertThat(strategy.buildWorkerJoinCommand(snapshot(), "10.0.0.5", "abc"))
                .containsIgnoringCase("timed out");
    }
}
