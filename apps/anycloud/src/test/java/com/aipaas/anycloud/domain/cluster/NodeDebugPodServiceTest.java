package com.aipaas.anycloud.domain.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.cluster.NodeDebugPodService.CreateRequest;
import com.aipaas.anycloud.domain.cluster.NodeDebugPodService.DebugPodResult;
import com.aipaas.anycloud.domain.cluster.NodeDebugPodService.NodeDebugPodException;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.Status;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class NodeDebugPodServiceTest extends AbstractUnitTest {

    private AgentSessionRegistry registry;
    private NodeDebugPodService svc;

    @BeforeEach
    void setUp() {
        registry = Mockito.mock(AgentSessionRegistry.class);
        svc = new com.aipaas.anycloud.domain.cluster.internal.NodeDebugPodServiceImpl(registry);
    }

    @Test
    void create_happyPath_returnsPodInfo() {
        stub(
                "c1",
                okResponse(Map.of(
                        "namespace", "kube-system",
                        "pod_name", "aipaas-node-debug-12345",
                        "node_name", "node-1",
                        "expires_at", "2026-12-31T23:59:59Z")));

        DebugPodResult result = svc.create("c1", new CreateRequest("node-1", null, null, null, null));

        assertThat(result.clusterName()).isEqualTo("c1");
        assertThat(result.namespace()).isEqualTo("kube-system");
        assertThat(result.podName()).isEqualTo("aipaas-node-debug-12345");
        assertThat(result.nodeName()).isEqualTo("node-1");
    }

    @Test
    void create_namespaceNotAllowed_throws() {
        stub(
                "c1",
                CommandResponse.newBuilder()
                        .setStatus(Status.PERMISSION_DENIED)
                        .setErrorCode("NAMESPACE_NOT_ALLOWED")
                        .setErrorMessage("kube-system not in allowlist")
                        .build());

        assertThatThrownBy(() -> svc.create("c1", new CreateRequest("node-1", "kube-system", null, null, null)))
                .isInstanceOf(NodeDebugPodException.class)
                .satisfies(ex ->
                        assertThat(((NodeDebugPodException) ex).errorCode()).isEqualTo("NAMESPACE_NOT_ALLOWED"));
    }

    @Test
    void create_noActiveSession_mapsToNO_ACTIVE_AGENT() {
        CompletableFuture<CommandResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(new AgentSessionRegistry.NoActiveSessionException("no session"));
        when(registry.sendCommand(eq("c1"), any(ControlMessage.Builder.class), anyInt()))
                .thenReturn(failed);

        assertThatThrownBy(() -> svc.create("c1", new CreateRequest("node-1", null, null, null, null)))
                .satisfies(ex ->
                        assertThat(((NodeDebugPodException) ex).errorCode()).isEqualTo("NO_ACTIVE_AGENT"));
    }

    private void stub(String clusterName, CommandResponse response) {
        when(registry.sendCommand(eq(clusterName), any(ControlMessage.Builder.class), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private static CommandResponse okResponse(Map<String, String> fields) {
        Struct.Builder b = Struct.newBuilder();
        fields.forEach(
                (k, v) -> b.putFields(k, Value.newBuilder().setStringValue(v).build()));
        return CommandResponse.newBuilder()
                .setStatus(Status.OK)
                .setResult(b.build())
                .build();
    }
}
