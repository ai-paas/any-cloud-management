package com.aipaas.anycloud.domain.cluster.kubeconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigExportService.IssueRequest;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigExportService.IssuedKubeconfig;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigExportService.KubeconfigExportException;
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

class KubeconfigExportServiceTest extends AbstractUnitTest {

    private AgentSessionRegistry registry;
    private KubeconfigExportService svc;

    @BeforeEach
    void setUp() {
        registry = Mockito.mock(AgentSessionRegistry.class);
        svc = new com.aipaas.anycloud.domain.cluster.kubeconfig.internal.KubeconfigExportServiceImpl(registry);
    }

    @Test
    void issue_happyPath_returnsKubeconfig() {
        stub(
                "c1",
                okResponse(Map.of(
                        "namespace", "default",
                        "service_account", "aipaas-user",
                        "expires_at", "2026-12-31T23:59:59Z",
                        "kubeconfig_yaml", "apiVersion: v1\nkind: Config\n...")));

        IssuedKubeconfig result = svc.issue("c1", new IssueRequest("default", "aipaas-user", 3600L, null, null));

        assertThat(result.clusterName()).isEqualTo("c1");
        assertThat(result.namespace()).isEqualTo("default");
        assertThat(result.serviceAccount()).isEqualTo("aipaas-user");
        assertThat(result.kubeconfigYaml()).contains("apiVersion: v1");
        assertThat(result.expiresAt()).isEqualTo("2026-12-31T23:59:59Z");
    }

    @Test
    void issue_serviceAccountNotFound_throws() {
        stub(
                "c1",
                CommandResponse.newBuilder()
                        .setStatus(Status.FAILED)
                        .setErrorCode("SERVICE_ACCOUNT_NOT_FOUND")
                        .setErrorMessage("sa default/ghost not found")
                        .build());

        assertThatThrownBy(() -> svc.issue("c1", new IssueRequest("default", "ghost", null, null, null)))
                .isInstanceOf(KubeconfigExportException.class)
                .satisfies(ex -> assertThat(((KubeconfigExportException) ex).errorCode())
                        .isEqualTo("SERVICE_ACCOUNT_NOT_FOUND"));
    }

    @Test
    void issue_noActiveSession_mapsToNO_ACTIVE_AGENT() {
        CompletableFuture<CommandResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(new AgentSessionRegistry.NoActiveSessionException("no session"));
        when(registry.sendCommand(eq("c1"), any(ControlMessage.Builder.class), anyInt()))
                .thenReturn(failed);

        assertThatThrownBy(() -> svc.issue("c1", new IssueRequest("default", "aipaas-user", null, null, null)))
                .satisfies(ex ->
                        assertThat(((KubeconfigExportException) ex).errorCode()).isEqualTo("NO_ACTIVE_AGENT"));
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
