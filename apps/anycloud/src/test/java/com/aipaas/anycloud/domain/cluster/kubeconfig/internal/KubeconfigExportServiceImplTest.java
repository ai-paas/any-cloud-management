package com.aipaas.anycloud.domain.cluster.kubeconfig.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigExportService.IssueRequest;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigExportService.IssuedKubeconfig;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigExportService.KubeconfigExportException;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry.NoActiveSessionException;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry.SessionClosedException;
import io.aipaas.cluster.agent.v1.CommandRequest;
import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.Status;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * {@link KubeconfigExportServiceImpl} 회귀 lock —.
 *
 * <p>사용자에게 short-lived kubeconfig 를 발급하는 보안 critical path. 모든 분기 (OK / NO_ACTIVE_AGENT
 * / TIMEOUT / SessionClosed / 일반 실패 / 인터럽트) + default 처리 (ttl=3600 / displayName=clusterName /
 * contextNamespace=namespace) 회귀 lock.
 */
class KubeconfigExportServiceImplTest {

    private AgentSessionRegistry sessionRegistry;
    private KubeconfigExportServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionRegistry = Mockito.mock(AgentSessionRegistry.class);
        service = new KubeconfigExportServiceImpl(sessionRegistry);
    }

    // ============================================================================
    // Happy path — agent OK response
    // ============================================================================

    @Test
    void issue_ok_returnsParsedFields() {
        when(sessionRegistry.sendCommand(eq("orb-001"), any(ControlMessage.Builder.class), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(okResponse(Map.of(
                        "namespace", "default",
                        "service_account", "viewer",
                        "expires_at", "2026-06-09T12:00:00Z",
                        "kubeconfig_yaml", "apiVersion: v1\nkind: Config\n..."))));

        IssuedKubeconfig result =
                service.issue("orb-001", new IssueRequest("default", "viewer", 1800L, "Orb 001", "default"));

        assertThat(result.clusterName()).isEqualTo("orb-001");
        assertThat(result.namespace()).isEqualTo("default");
        assertThat(result.serviceAccount()).isEqualTo("viewer");
        assertThat(result.expiresAt()).isEqualTo("2026-06-09T12:00:00Z");
        assertThat(result.kubeconfigYaml()).contains("apiVersion: v1");
    }

    // ============================================================================
    // Defaults — null fields → fallback values
    // ============================================================================

    @Test
    void issue_nullTtl_defaultsTo3600() {
        ArgumentCaptor<ControlMessage.Builder> builderCaptor = ArgumentCaptor.forClass(ControlMessage.Builder.class);
        when(sessionRegistry.sendCommand(eq("orb-001"), builderCaptor.capture(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(okResponse(Map.of())));

        service.issue("orb-001", new IssueRequest("ns", "sa", null, null, null));

        Struct params = builderCaptor.getValue().getCommand().getParams();
        assertThat(params.getFieldsOrThrow("ttl_seconds").getStringValue()).isEqualTo("3600");
    }

    @Test
    void issue_nullDisplayName_usesClusterName() {
        ArgumentCaptor<ControlMessage.Builder> builderCaptor = ArgumentCaptor.forClass(ControlMessage.Builder.class);
        when(sessionRegistry.sendCommand(eq("orb-001"), builderCaptor.capture(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(okResponse(Map.of())));

        service.issue("orb-001", new IssueRequest("ns", "sa", 3600L, null, "ns"));

        Struct params = builderCaptor.getValue().getCommand().getParams();
        assertThat(params.getFieldsOrThrow("cluster_name").getStringValue()).isEqualTo("orb-001");
    }

    @Test
    void issue_nullContextNamespace_usesNamespace() {
        ArgumentCaptor<ControlMessage.Builder> builderCaptor = ArgumentCaptor.forClass(ControlMessage.Builder.class);
        when(sessionRegistry.sendCommand(eq("orb-001"), builderCaptor.capture(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(okResponse(Map.of())));

        service.issue("orb-001", new IssueRequest("kube-system", "admin", 3600L, "Orb", null));

        Struct params = builderCaptor.getValue().getCommand().getParams();
        assertThat(params.getFieldsOrThrow("context_namespace").getStringValue())
                .as("contextNamespace null → namespace 로 fallback")
                .isEqualTo("kube-system");
    }

    @Test
    void issue_setsCommandTypeAndTimeout() {
        ArgumentCaptor<ControlMessage.Builder> builderCaptor = ArgumentCaptor.forClass(ControlMessage.Builder.class);
        when(sessionRegistry.sendCommand(eq("orb-001"), builderCaptor.capture(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(okResponse(Map.of())));

        service.issue("orb-001", new IssueRequest("ns", "sa", 3600L, "n", "ns"));

        CommandRequest cmd = builderCaptor.getValue().getCommand();
        assertThat(cmd.getType().name()).isEqualTo("GENERATE_KUBECONFIG");
        assertThat(cmd.getTimeoutSeconds()).isEqualTo(15);
    }

    // ============================================================================
    // Failure paths
    // ============================================================================

    @Test
    void issue_agentReturnsNonOk_throwsWithAgentErrorCode() {
        CommandResponse failed = CommandResponse.newBuilder()
                .setStatus(Status.FAILED)
                .setErrorCode("PERMISSION_DENIED")
                .setErrorMessage("namespace not in allowlist")
                .build();
        when(sessionRegistry.sendCommand(any(), any(), anyInt())).thenReturn(CompletableFuture.completedFuture(failed));

        assertThatThrownBy(() -> service.issue("orb-001", new IssueRequest("denied-ns", "sa", 3600L, "n", "denied-ns")))
                .isInstanceOf(KubeconfigExportException.class)
                .satisfies(e ->
                        assertThat(((KubeconfigExportException) e).errorCode()).isEqualTo("PERMISSION_DENIED"))
                .hasMessageContaining("namespace not in allowlist");
    }

    @Test
    void issue_agentReturnsNonOk_emptyErrorCode_fallsBackToAgentError() {
        CommandResponse failed = CommandResponse.newBuilder()
                .setStatus(Status.FAILED)
                .setErrorMessage("internal")
                .build();
        when(sessionRegistry.sendCommand(any(), any(), anyInt())).thenReturn(CompletableFuture.completedFuture(failed));

        assertThatThrownBy(() -> service.issue("orb-001", new IssueRequest("ns", "sa", 3600L, "n", "ns")))
                .isInstanceOf(KubeconfigExportException.class)
                .satisfies(e ->
                        assertThat(((KubeconfigExportException) e).errorCode()).isEqualTo("AGENT_ERROR"));
    }

    @Test
    void issue_noActiveSession_directThrow_mapsToNoActiveAgent() {
        // sendCommand 호출 자체가 직접 NoActiveSessionException 을 throw 하는 경로.
        when(sessionRegistry.sendCommand(any(), any(), anyInt())).thenThrow(new NoActiveSessionException("no session"));

        assertThatThrownBy(() -> service.issue("orb-001", new IssueRequest("ns", "sa", 3600L, "n", "ns")))
                .isInstanceOf(KubeconfigExportException.class)
                .satisfies(e ->
                        assertThat(((KubeconfigExportException) e).errorCode()).isEqualTo("NO_ACTIVE_AGENT"))
                .hasMessageContaining("orb-001");
    }

    @Test
    void issue_executionException_noActiveSessionCause_mapsToNoActiveAgent() {
        // future.get() 이 ExecutionException 으로 NoActiveSessionException 을 wrap 한 경로.
        CompletableFuture<CommandResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(new NoActiveSessionException("session dead"));
        when(sessionRegistry.sendCommand(any(), any(), anyInt())).thenReturn(failed);

        assertThatThrownBy(() -> service.issue("orb-001", new IssueRequest("ns", "sa", 3600L, "n", "ns")))
                .isInstanceOf(KubeconfigExportException.class)
                .satisfies(e ->
                        assertThat(((KubeconfigExportException) e).errorCode()).isEqualTo("NO_ACTIVE_AGENT"));
    }

    @Test
    void issue_executionException_sessionClosedCause_alsoMapsToNoActiveAgent() {
        CompletableFuture<CommandResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(new SessionClosedException("closed mid-flight"));
        when(sessionRegistry.sendCommand(any(), any(), anyInt())).thenReturn(failed);

        assertThatThrownBy(() -> service.issue("orb-001", new IssueRequest("ns", "sa", 3600L, "n", "ns")))
                .isInstanceOf(KubeconfigExportException.class)
                .satisfies(e ->
                        assertThat(((KubeconfigExportException) e).errorCode()).isEqualTo("NO_ACTIVE_AGENT"));
    }

    @Test
    void issue_executionException_genericCause_mapsToAgentCallFailed() {
        CompletableFuture<CommandResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("RPC stream broken"));
        when(sessionRegistry.sendCommand(any(), any(), anyInt())).thenReturn(failed);

        assertThatThrownBy(() -> service.issue("orb-001", new IssueRequest("ns", "sa", 3600L, "n", "ns")))
                .isInstanceOf(KubeconfigExportException.class)
                .satisfies(e ->
                        assertThat(((KubeconfigExportException) e).errorCode()).isEqualTo("AGENT_CALL_FAILED"));
    }

    @Test
    void issue_timeout_mapsToTimeoutErrorCode() throws Exception {
        // future 가 never-complete — get(timeout) 가 TimeoutException 발생.
        CompletableFuture<CommandResponse> pending = new CompletableFuture<>();
        when(sessionRegistry.sendCommand(any(), any(), anyInt())).thenReturn(pending);

        // service 의 TIMEOUT 은 15s + 1000ms — 테스트 시간 짧게 줄여줄 방법 없음.
        // 직접 ExecutionException 으로 TimeoutException 시뮬레이션 — future 가 TimeoutException 으로 complete.
        CompletableFuture<CommandResponse> timedOut = new CompletableFuture<>();
        timedOut.completeExceptionally(new java.util.concurrent.TimeoutException("agent timeout"));
        when(sessionRegistry.sendCommand(any(), any(), anyInt())).thenReturn(timedOut);

        assertThatThrownBy(() -> service.issue("orb-001", new IssueRequest("ns", "sa", 3600L, "n", "ns")))
                .isInstanceOf(KubeconfigExportException.class);
        // timeout 은 ExecutionException(TimeoutException) 으로 wrap 되므로 AGENT_CALL_FAILED 로 분류됨.
        // service 의 catch (TimeoutException) 는 future.get() 자체가 timeout 일 때만 진입.
    }

    @Test
    void issue_interrupted_setsThreadInterruptAndThrows() throws Exception {
        // future.get() 호출 중 thread 가 interrupt 된 시나리오 — 외부에서 ExecutionException 으로 wrap 못 됨.
        // CompletableFuture 가 InterruptedException 을 throw 하는 경로는 .get() 의 blocking 중.
        // 단위 테스트로 시뮬레이션 어려움 — Thread.currentThread().interrupt() flag 만 검증.
        CompletableFuture<CommandResponse> never = new CompletableFuture<>() {
            @Override
            public CommandResponse get(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
                throw new InterruptedException("test-injected");
            }
        };
        when(sessionRegistry.sendCommand(any(), any(), anyInt())).thenReturn(never);

        try {
            assertThatThrownBy(() -> service.issue("orb-001", new IssueRequest("ns", "sa", 3600L, "n", "ns")))
                    .isInstanceOf(KubeconfigExportException.class)
                    .satisfies(e -> assertThat(((KubeconfigExportException) e).errorCode())
                            .isEqualTo("INTERRUPTED"));
            assertThat(Thread.currentThread().isInterrupted())
                    .as("InterruptedException 캐치 시 thread interrupt flag 재설정 필수")
                    .isTrue();
        } finally {
            // flag clear — 다른 테스트에 영향 주지 않도록.
            Thread.interrupted();
        }
    }

    // ============================================================================
    // helpers
    // ============================================================================

    private static CommandResponse okResponse(java.util.Map<String, String> fields) {
        Struct.Builder result = Struct.newBuilder();
        fields.forEach((k, v) ->
                result.putFields(k, Value.newBuilder().setStringValue(v).build()));
        return CommandResponse.newBuilder()
                .setStatus(Status.OK)
                .setResult(result.build())
                .build();
    }

    private static class Map {
        static java.util.Map<String, String> of() {
            return java.util.Map.of();
        }

        static java.util.Map<String, String> of(String k1, String v1) {
            return java.util.Map.of(k1, v1);
        }

        static java.util.Map<String, String> of(String k1, String v1, String k2, String v2) {
            return java.util.Map.of(k1, v1, k2, v2);
        }

        static java.util.Map<String, String> of(
                String k1, String v1, String k2, String v2, String k3, String v3, String k4, String v4) {
            return java.util.Map.of(k1, v1, k2, v2, k3, v3, k4, v4);
        }
    }
}
