package io.aipaas.cluster.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.aipaas.cluster.agent.runtime.AgentSessionRegistry.AgentSession;
import io.aipaas.cluster.agent.v1.CommandResponse;
import io.aipaas.cluster.agent.v1.ControlMessage;
import io.aipaas.cluster.agent.v1.Status;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * KubeResourceService 회귀 — flag / session / 응답 status 의 다양한 조합 검증.
 *
 * <p>STRICT_STUBS 를 쓴다. 느슨한 mock 에서는 production 이 부르는 것과 다른 오버로드를 stub 해도
 * 조용히 null 을 돌려주고 NPE 로만 드러난다 — listResources 에 fieldSelector 인자가 늘었을 때
 * 실제로 그렇게 깨졌다. strict 모드는 호출 시점에 PotentialStubbingProblem 으로 알린다.
 */
@ExtendWith(MockitoExtension.class)
class KubeResourceServiceTest {

    @Mock
    private AgentSessionRegistry registry;

    @Mock
    private AgentCommandRouter router;

    private KubeResourceService svc;

    @BeforeEach
    void setUp() {
        svc = new KubeResourceService(registry, router, new ObjectMapper(), true, 5);
    }

    private KubeResourceService disabledSvc() {
        return new KubeResourceService(registry, router, new ObjectMapper(), false, 5);
    }

    private AgentSession fakeSession(String clusterName) {
        @SuppressWarnings("unchecked")
        StreamObserver<ControlMessage> obs = (StreamObserver<ControlMessage>) Mockito.mock(StreamObserver.class);
        return new AgentSession(clusterName, "instance-1", obs, System.currentTimeMillis());
    }

    // ===== isActiveFor =====

    @Test
    void isActiveFor_flagOff_returnsFalse() {
        assertThat(disabledSvc().isActiveFor("c1")).isFalse();
        Mockito.verifyNoInteractions(registry);
    }

    @Test
    void isActiveFor_flagOnButNoSession_returnsFalse() {
        when(registry.find("c1")).thenReturn(Optional.empty());
        assertThat(svc.isActiveFor("c1")).isFalse();
    }

    @Test
    void isActiveFor_flagOnAndSession_returnsTrue() {
        when(registry.find("c1")).thenReturn(Optional.of(fakeSession("c1")));
        assertThat(svc.isActiveFor("c1")).isTrue();
    }

    // ===== GET_LOG =====

    @Test
    void getPodLogs_agentOK_returnsLogString() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.OK)
                .setResult(Struct.newBuilder()
                        .putFields(
                                "log",
                                Value.newBuilder()
                                        .setStringValue("line A\nline B\n")
                                        .build())
                        .build())
                .build();
        when(router.getLog(eq("c1"), eq("web"), eq("nginx"), anyString(), anyInt(), anyBoolean(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(resp));

        assertThat(svc.getPodLogs("c1", "web", "nginx", "", 100, false, 0)).isEqualTo("line A\nline B\n");
    }

    @Test
    void getPodLogs_agentReturnsFailed_throws() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.FAILED)
                .setErrorCode("K8S_GET_LOG_FAILED")
                .setErrorMessage("apiserver down")
                .build();
        when(router.getLog(anyString(), anyString(), anyString(), anyString(), anyInt(), anyBoolean(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(resp));

        assertThatThrownBy(() -> svc.getPodLogs("c1", "web", "nginx", "", 100, false, 0))
                .isInstanceOf(KubeRoutingException.class)
                .hasMessageContaining("K8S_GET_LOG_FAILED")
                .hasMessageContaining("apiserver down");
    }

    @Test
    void getPodLogs_futureFailsWithNoActiveSession_wrapsToRoutingException() {
        CompletableFuture<CommandResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(new AgentSessionRegistry.NoActiveSessionException("no session"));
        when(router.getLog(anyString(), anyString(), anyString(), anyString(), anyInt(), anyBoolean(), anyInt()))
                .thenReturn(failed);

        assertThatThrownBy(() -> svc.getPodLogs("c1", "web", "nginx", "", 100, false, 0))
                .isInstanceOf(KubeRoutingException.class)
                .hasMessageContaining("No active agent session");
    }

    @Test
    void getPodLogs_agentResponseMissingLogField_throws() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.OK)
                .setResult(Struct.newBuilder().build())
                .build();
        when(router.getLog(anyString(), anyString(), anyString(), anyString(), anyInt(), anyBoolean(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(resp));

        assertThatThrownBy(() -> svc.getPodLogs("c1", "web", "nginx", "", 100, false, 0))
                .isInstanceOf(KubeRoutingException.class)
                .hasMessageContaining("missing 'log' field");
    }

    @Test
    void getPodLogs_normalizesNullsToDefaults() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.OK)
                .setResult(Struct.newBuilder()
                        .putFields(
                                "log", Value.newBuilder().setStringValue("ok").build())
                        .build())
                .build();
        when(router.getLog(eq("c1"), eq("default"), eq("nginx"), eq(""), eq(100), eq(false), eq(0)))
                .thenReturn(CompletableFuture.completedFuture(resp));

        assertThat(svc.getPodLogs("c1", null, "nginx", null, null, false, null)).isEqualTo("ok");
    }

    // ===== DELETE_RESOURCE =====

    @Test
    void deleteResource_agentOK_returnsTrue() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.OK)
                .setResult(Struct.newBuilder()
                        .putFields(
                                "deleted", Value.newBuilder().setBoolValue(true).build())
                        .build())
                .build();
        when(router.deleteResource(eq("c1"), eq("web"), eq("pod"), eq("nginx")))
                .thenReturn(CompletableFuture.completedFuture(resp));

        assertThat(svc.deleteResource("c1", "web", "pod", "nginx")).isTrue();
    }

    @Test
    void deleteResource_agentReturnsFailed_throws() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.FAILED)
                .setErrorCode("K8S_DELETE_FAILED")
                .setErrorMessage("not found")
                .build();
        when(router.deleteResource(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(resp));

        assertThatThrownBy(() -> svc.deleteResource("c1", "web", "pod", "nginx"))
                .isInstanceOf(KubeRoutingException.class)
                .hasMessageContaining("K8S_DELETE_FAILED");
    }

    @Test
    void deleteResource_permissionDenied_throws() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.PERMISSION_DENIED)
                .setErrorCode("NAMESPACE_NOT_ALLOWED")
                .setErrorMessage("namespace production not in allowlist")
                .build();
        when(router.deleteResource(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(resp));

        assertThatThrownBy(() -> svc.deleteResource("c1", "production", "pod", "x"))
                .isInstanceOf(KubeRoutingException.class)
                .hasMessageContaining("NAMESPACE_NOT_ALLOWED");
    }

    @Test
    void deleteResource_unsupportedKind_throws() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.INVALID_PARAMS)
                .setErrorCode("UNSUPPORTED_KIND")
                .setErrorMessage("unsupported kind: unicornresource")
                .build();
        when(router.deleteResource(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(resp));

        assertThatThrownBy(() -> svc.deleteResource("c1", "web", "unicornresource", "x"))
                .isInstanceOf(KubeRoutingException.class)
                .hasMessageContaining("UNSUPPORTED_KIND");
    }

    @Test
    void deleteResource_missingDeletedField_returnsFalse() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.OK)
                .setResult(Struct.newBuilder().build())
                .build();
        when(router.deleteResource(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(resp));

        assertThat(svc.deleteResource("c1", "web", "pod", "nginx")).isFalse();
    }

    // ===== GET_RESOURCE =====

    @Test
    void getResource_agentOK_returnsParsedJsonNode() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.OK)
                .setResult(Struct.newBuilder()
                        .putFields(
                                "resource",
                                Value.newBuilder()
                                        .setStringValue("{\"kind\":\"Pod\",\"metadata\":{\"name\":\"nginx-abc\"}}")
                                        .build())
                        .build())
                .build();
        when(router.getResource(eq("c1"), eq("web"), eq("pod"), eq("nginx-abc")))
                .thenReturn(CompletableFuture.completedFuture(resp));

        JsonNode node = svc.getResource("c1", "web", "pod", "nginx-abc");
        assertThat(node.get("kind").asText()).isEqualTo("Pod");
        assertThat(node.get("metadata").get("name").asText()).isEqualTo("nginx-abc");
    }

    @Test
    void getResource_invalidJson_throws() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.OK)
                .setResult(Struct.newBuilder()
                        .putFields(
                                "resource",
                                Value.newBuilder()
                                        .setStringValue("{not valid JSON")
                                        .build())
                        .build())
                .build();
        when(router.getResource(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(resp));

        assertThatThrownBy(() -> svc.getResource("c1", "web", "pod", "nginx"))
                .isInstanceOf(KubeRoutingException.class)
                .hasMessageContaining("parse");
    }

    @Test
    void getResource_agentFailed_throws() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.FAILED)
                .setErrorCode("K8S_GET_FAILED")
                .setErrorMessage("not found")
                .build();
        when(router.getResource(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(resp));

        assertThatThrownBy(() -> svc.getResource("c1", "web", "pod", "nginx"))
                .isInstanceOf(KubeRoutingException.class)
                .hasMessageContaining("K8S_GET_FAILED");
    }

    @Test
    void getResource_missingResourceField_throws() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.OK)
                .setResult(Struct.newBuilder().build())
                .build();
        when(router.getResource(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(resp));

        assertThatThrownBy(() -> svc.getResource("c1", "web", "pod", "nginx"))
                .isInstanceOf(KubeRoutingException.class)
                .hasMessageContaining("missing 'resource' field");
    }

    // ===== LIST_RESOURCES =====

    @Test
    void listResourcesPaginated_agentOK_returnsPage() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.OK)
                .setResult(Struct.newBuilder()
                        .putFields(
                                "items",
                                Value.newBuilder()
                                        .setStringValue("[{\"kind\":\"Pod\",\"metadata\":{\"name\":\"nginx-1\"}}]")
                                        .build())
                        .putFields(
                                "continue_token",
                                Value.newBuilder().setStringValue("tok-next").build())
                        .putFields(
                                "returned_count",
                                Value.newBuilder().setNumberValue(1).build())
                        .build())
                .build();
        when(router.listResources(eq("c1"), eq("web"), eq("pods"), eq(50), eq(""), eq(""), eq("")))
                .thenReturn(CompletableFuture.completedFuture(resp));

        KubeResourcePage page = svc.listResourcesPaginated("c1", "web", "pods", 50, "", "");

        assertThat(page.clusterName()).isEqualTo("c1");
        assertThat(page.namespace()).isEqualTo("web");
        assertThat(page.kind()).isEqualTo("pods");
        assertThat(page.continueToken()).isEqualTo("tok-next");
        assertThat(page.returnedCount()).isEqualTo(1);
        assertThat(page.items().isArray()).isTrue();
        assertThat(page.items().get(0).get("kind").asText()).isEqualTo("Pod");
    }

    @Test
    void listResourcesPaginated_allNamespaces_returnsNullNamespace() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.OK)
                .setResult(Struct.newBuilder()
                        .putFields(
                                "items", Value.newBuilder().setStringValue("[]").build())
                        .putFields(
                                "returned_count",
                                Value.newBuilder().setNumberValue(0).build())
                        .build())
                .build();
        when(router.listResources(
                        anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(resp));

        KubeResourcePage page = svc.listResourcesPaginated("c1", null, "pods", 50, "", "");
        assertThat(page.namespace()).isNull();
        assertThat(page.continueToken()).isNull();
    }

    @Test
    void listResourcesPaginated_agentFailed_throws() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.FAILED)
                .setErrorCode("K8S_LIST_FAILED")
                .setErrorMessage("forbidden")
                .build();
        when(router.listResources(
                        anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(resp));

        assertThatThrownBy(() -> svc.listResourcesPaginated("c1", "web", "pods", 50, "", ""))
                .isInstanceOf(KubeRoutingException.class)
                .hasMessageContaining("K8S_LIST_FAILED");
    }

    @Test
    void listResourcesPaginated_invalidJson_throws() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.OK)
                .setResult(Struct.newBuilder()
                        .putFields(
                                "items",
                                Value.newBuilder()
                                        .setStringValue("{ broken JSON")
                                        .build())
                        .build())
                .build();
        when(router.listResources(
                        anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(resp));

        assertThatThrownBy(() -> svc.listResourcesPaginated("c1", "web", "pods", 50, "", ""))
                .isInstanceOf(KubeRoutingException.class)
                .hasMessageContaining("parse");
    }

    // ===== APPLY_MANIFEST =====

    @Test
    void applyResource_singleResource_returnsSingleObject() {
        Struct pod = Struct.newBuilder()
                .putFields("kind", Value.newBuilder().setStringValue("Pod").build())
                .putFields("name", Value.newBuilder().setStringValue("nginx").build())
                .build();
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.OK)
                .setResult(Struct.newBuilder()
                        .putFields(
                                "applied",
                                Value.newBuilder()
                                        .setListValue(ListValue.newBuilder()
                                                .addValues(Value.newBuilder().setStructValue(pod))
                                                .build())
                                        .build())
                        .build())
                .build();
        when(router.applyManifest(eq("c1"), eq("web"), eq("manifest"), eq(false), eq(false)))
                .thenReturn(CompletableFuture.completedFuture(resp));

        JsonNode node = svc.applyResource("c1", "web", "manifest");

        assertThat(node.isArray()).isFalse();
        assertThat(node.get("kind").asText()).isEqualTo("Pod");
    }

    @Test
    void applyResource_multipleResources_returnsArray() {
        Struct r1 = Struct.newBuilder()
                .putFields("kind", Value.newBuilder().setStringValue("Service").build())
                .build();
        Struct r2 = Struct.newBuilder()
                .putFields(
                        "kind", Value.newBuilder().setStringValue("Deployment").build())
                .build();
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.OK)
                .setResult(Struct.newBuilder()
                        .putFields(
                                "applied",
                                Value.newBuilder()
                                        .setListValue(ListValue.newBuilder()
                                                .addValues(Value.newBuilder().setStructValue(r1))
                                                .addValues(Value.newBuilder().setStructValue(r2))
                                                .build())
                                        .build())
                        .build())
                .build();
        when(router.applyManifest(anyString(), anyString(), anyString(), eq(false), eq(false)))
                .thenReturn(CompletableFuture.completedFuture(resp));

        JsonNode node = svc.applyResource("c1", "web", "multi");

        assertThat(node.isArray()).isTrue();
        assertThat(node.size()).isEqualTo(2);
        assertThat(node.get(0).get("kind").asText()).isEqualTo("Service");
        assertThat(node.get(1).get("kind").asText()).isEqualTo("Deployment");
    }

    @Test
    void applyResource_agentFailed_throws() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.FAILED)
                .setErrorCode("K8S_APPLY_FAILED")
                .setErrorMessage("admission webhook denied")
                .build();
        when(router.applyManifest(anyString(), anyString(), anyString(), eq(false), eq(false)))
                .thenReturn(CompletableFuture.completedFuture(resp));

        assertThatThrownBy(() -> svc.applyResource("c1", "web", "manifest"))
                .isInstanceOf(KubeRoutingException.class)
                .hasMessageContaining("K8S_APPLY_FAILED");
    }

    @Test
    void applyResource_permissionDenied_throws() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.PERMISSION_DENIED)
                .setErrorCode("NAMESPACE_NOT_ALLOWED")
                .setErrorMessage("namespace production not in allowlist")
                .build();
        when(router.applyManifest(anyString(), anyString(), anyString(), eq(false), eq(false)))
                .thenReturn(CompletableFuture.completedFuture(resp));

        assertThatThrownBy(() -> svc.applyResource("c1", "production", "manifest"))
                .isInstanceOf(KubeRoutingException.class)
                .hasMessageContaining("NAMESPACE_NOT_ALLOWED");
    }

    @Test
    void applyResource_missingAppliedField_throws() {
        CommandResponse resp = CommandResponse.newBuilder()
                .setStatus(Status.OK)
                .setResult(Struct.newBuilder().build())
                .build();
        when(router.applyManifest(anyString(), anyString(), anyString(), eq(false), eq(false)))
                .thenReturn(CompletableFuture.completedFuture(resp));

        assertThatThrownBy(() -> svc.applyResource("c1", "web", "manifest"))
                .isInstanceOf(KubeRoutingException.class)
                .hasMessageContaining("missing 'applied' list");
    }
}
