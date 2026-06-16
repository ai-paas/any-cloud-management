package com.aipaas.anycloud.domain.kube.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipaas.anycloud.common.error.handler.GlobalExceptionHandler;
import com.aipaas.anycloud.domain.kube.KindResolver;
import com.aipaas.anycloud.domain.kube.KubeService;
import com.aipaas.anycloud.domain.kube.PagedKubeResourceResponse;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.runtime.ResolvedResource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Cluster sub-resource (K8s) RESTful 라우팅 회귀 방지.
 *  - GET /v1/clusters/{c}/namespaces/{ns}/{kind}        → list (paginated)
 *  - GET /v1/clusters/{c}/namespaces/{ns}/{kind}/{name} → single
 *  - DELETE 동일                                         → delete
 */
class ClusterKubernetesControllerTest extends AbstractUnitTest {

    @Mock
    KubeService kubeService;

    @Mock
    KindResolver kindResolver;

    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // 모든 kind 에 대해 namespaced=true 로 기본 응답 → 기존 routing 회귀 테스트의 의도
        // (path namespace 가 그대로 전달되거나 _all/- 일 때만 null 변환) 와 호환.
        // K8sKinds 의 hardcoded cluster-scoped (nodes, persistentvolumes 등) 은 개별 테스트에서
        // stub override.
        org.mockito.Mockito.lenient()
                .when(kindResolver.resolve(
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    String input = inv.getArgument(1);
                    boolean clusterScoped = com.aipaas.anycloud.domain.kube.model.K8sKinds.isClusterScoped(input);
                    return new ResolvedResource(input, null, null, "", "v1", !clusterScoped, List.<String>of());
                });
        mvc = MockMvcBuilders.standaloneSetup(new ClusterKubernetesController(kubeService, null, kindResolver))
                .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
                .build();
    }

    @Test
    void list_paginatedPods_returns200_withItemsAndContinue() throws Exception {
        PagedKubeResourceResponse stub = PagedKubeResourceResponse.builder()
                .clusterName("demo-aws-01")
                .namespace("web")
                .resourceType("pods")
                .items(json.createArrayNode())
                .continueToken("tok-next")
                .returnedItemCount(0)
                .build();
        when(kubeService.listResourcesPaginated(anyString(), anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(stub);

        mvc.perform(get("/v1/clusters/demo-aws-01/namespaces/web/pods").param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.continueToken").value("tok-next"))
                .andExpect(jsonPath("$.data.resourceType").value("pods"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"_all", "-"})
    void list_allNamespacesSentinel_invokesNullNs(String sentinel) throws Exception {
        when(kubeService.listResourcesPaginated(anyString(), any(), anyString(), anyInt(), any(), any()))
                .thenReturn(PagedKubeResourceResponse.builder()
                        .clusterName("x")
                        .resourceType("pods")
                        .items(json.createArrayNode())
                        .returnedItemCount(0)
                        .build());

        // _all / - 모두 sentinel — 컨트롤러가 ns=null 로 전달해야 한다.
        mvc.perform(get("/v1/clusters/demo-aws-01/namespaces/" + sentinel + "/pods"))
                .andExpect(status().isOk());
        verify(kubeService).listResourcesPaginated(eq("demo-aws-01"), isNull(), eq("pods"), anyInt(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"nodes", "namespaces", "persistentvolumes", "storageclasses", "customresourcedefinitions"})
    void list_clusterScopedKinds_ignoreNamespacePath(String kind) throws Exception {
        when(kubeService.listResourcesPaginated(anyString(), any(), anyString(), anyInt(), any(), any()))
                .thenReturn(PagedKubeResourceResponse.builder()
                        .clusterName("x")
                        .resourceType(kind)
                        .items(json.createArrayNode())
                        .returnedItemCount(0)
                        .build());

        // 어떤 namespace 값이 와도 cluster-scoped kind 면 서비스 호출에는 ns=null 전달.
        mvc.perform(get("/v1/clusters/demo-aws-01/namespaces/web/" + kind)).andExpect(status().isOk());
        verify(kubeService).listResourcesPaginated(eq("demo-aws-01"), isNull(), eq(kind), anyInt(), any(), any());
    }

    @Test
    void getOne_returnsResource() throws Exception {
        when(kubeService.getResource(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(json.createObjectNode().put("name", "nginx-abc"));
        mvc.perform(get("/v1/clusters/demo-aws-01/namespaces/web/pods/nginx-abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("nginx-abc"));
    }

    @Test
    void delete_returnsOk_andDeletedFlag() throws Exception {
        when(kubeService.deleteResource(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        mvc.perform(delete("/v1/clusters/demo-aws-01/namespaces/web/pods/nginx-abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
    }

    @Test
    void getPodLogs_defaultParams_returnsTextPlain() throws Exception {
        // T2 (#10a): kubectl logs 등가 endpoint. tailLines/previous/sinceSeconds 옵션 모두 default.
        when(kubeService.getPodLogs(
                        anyString(),
                        anyString(),
                        anyString(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.eq(false),
                        org.mockito.ArgumentMatchers.isNull()))
                .thenReturn("2026-05-12 10:00:00 server started\n2026-05-12 10:00:01 ready\n");

        mvc.perform(get("/v1/clusters/demo-aws-01/namespaces/web/pods/nginx-abc/logs"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith("text/plain"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("server started")));
    }

    @Test
    void getPodLogs_withTailAndSinceAndPreviousAndContainer_forwardsAllParams() throws Exception {
        when(kubeService.getPodLogs(
                        eq("demo-aws-01"), eq("web"), eq("nginx-abc"), eq("sidecar"), eq(500), eq(true), eq(600)))
                .thenReturn("previous container log");

        mvc.perform(get("/v1/clusters/demo-aws-01/namespaces/web/pods/nginx-abc/logs")
                        .param("tailLines", "500")
                        .param("previous", "true")
                        .param("sinceSeconds", "600")
                        .param("container", "sidecar"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string("previous container log"));

        verify(kubeService).getPodLogs("demo-aws-01", "web", "nginx-abc", "sidecar", 500, true, 600);
    }

    // Note: @Min/@Max validation on query params (tailLines 1..10000, sinceSeconds 1..86400) 는 실제
    // Spring context 의 MethodValidationPostProcessor 를 통해 동작. 표준 standaloneSetup MockMvc 는
    // 해당 interceptor 가 없어 회귀 테스트로 보호하기 어려움 — service 단에서도
    // Math.min(MAX_TAIL_LINES) 캡으로 defense-in-depth 적용됨 (KubeServiceImpl.getPodLogs).

    @Test
    void apply_yamlManifest_returns201_andForwardsToService() throws Exception {
        when(kubeService.applyResource(anyString(), any(), anyString(), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(json.createObjectNode().put("kind", "Deployment").put("status", "applied"));

        String yaml =
                """
				apiVersion: apps/v1
				kind: Deployment
				metadata:
				  name: nginx
				spec:
				  replicas: 1
				""";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/v1/clusters/demo-aws-01/namespaces/web/deployments")
                        .contentType("application/yaml")
                        .content(yaml))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("Deployment"));

        verify(kubeService)
                .applyResource(
                        eq("demo-aws-01"),
                        eq("web"),
                        org.mockito.ArgumentMatchers.contains("nginx"),
                        org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    void apply_clusterScopedKind_forwardsNullNamespace() throws Exception {
        when(kubeService.applyResource(anyString(), any(), anyString(), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(json.createObjectNode().put("kind", "Namespace"));

        String yaml = "apiVersion: v1\nkind: Namespace\nmetadata:\n  name: new-ns\n";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/v1/clusters/demo-aws-01/namespaces/-/namespaces")
                        .contentType("application/yaml")
                        .content(yaml))
                .andExpect(status().isCreated());

        verify(kubeService)
                .applyResource(
                        eq("demo-aws-01"),
                        isNull(),
                        org.mockito.ArgumentMatchers.contains("new-ns"),
                        org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    void apply_jsonManifest_alsoAccepted() throws Exception {
        when(kubeService.applyResource(anyString(), any(), anyString(), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(json.createObjectNode().put("kind", "ConfigMap"));

        String jsonBody = "{\"apiVersion\":\"v1\",\"kind\":\"ConfigMap\",\"metadata\":{\"name\":\"app-config\"}}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/v1/clusters/demo-aws-01/namespaces/web/configmaps")
                        .contentType("application/json")
                        .content(jsonBody))
                .andExpect(status().isCreated());
    }

    // NOTE: path 정규식의 실제 검증 거동은 PathValidationRegressionTest 가 커버한다 —
    // Spring AOP MethodValidationInterceptor 를 controller 에 직접 적용해 standalone MockMvc
    // 환경에서도 @Validated path/query parameter 검증이 ConstraintViolationException → 400 으로
    // 정상 매핑되는지 회귀 방지.
}
