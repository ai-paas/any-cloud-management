package com.aipaas.anycloud.domain.cluster.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aipaas.anycloud.common.error.handler.GlobalExceptionHandler;
import com.aipaas.anycloud.domain.chart.ChartService;
import com.aipaas.anycloud.domain.cluster.ClusterFacade;
import com.aipaas.anycloud.domain.kube.KindResolver;
import com.aipaas.anycloud.domain.kube.KubeService;
import com.aipaas.anycloud.domain.kube.PagedKubeResourceResponse;
import com.aipaas.anycloud.domain.kube.web.ClusterKubernetesController;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.operation.web.OperationController;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;

/**
 * v1 controller 의 path / query parameter @Pattern / @Size 검증 회귀 방지.
 * <p>
 * 표준 MockMvc 의 {@code standaloneSetup} 에선 {@code @Validated} 클래스의 path/query
 * parameter 검증을 트리거하는 {@code MethodValidationPostProcessor} 가 적용되지 않아
 * 정규식 위반이 그냥 통과한다. 본 테스트는 Spring AOP {@link ProxyFactory} 로 controller 를
 * 직접 감싸 {@link MethodValidationInterceptor} 를 적용함으로써 path validation 이 실제로
 * 400 을 만들도록 회귀를 잡는다.
 * <p>
 * Docker / DB / 전체 Spring context 모두 불필요. 모든 service 는 Mockito.
 */
class PathValidationRegressionTest extends AbstractUnitTest {

    private final ClusterFacade clusterFacade = mock(ClusterFacade.class);
    private final OperationService operationService = mock(OperationService.class);
    private final KubeService kubeService = mock(KubeService.class);
    private final ChartService chartService = mock(ChartService.class);
    private final KindResolver kindResolver = mock(KindResolver.class);
    private final io.aipaas.cluster.agent.observability.port.ClusterCapabilitiesSink sink =
            mock(io.aipaas.cluster.agent.observability.port.ClusterCapabilitiesSink.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // Path validation 위반 테스트가 주 목적이므로 resolve() 가 호출될 일은 거의 없지만, K8s
        // kind path 가 정상 형태일 때 NPE 방지용 default stub.
        org.mockito.Mockito.lenient()
                .when(kindResolver.resolve(anyString(), anyString()))
                .thenReturn(new io.aipaas.cluster.agent.runtime.ResolvedResource(
                        "pods", null, null, "", "v1", true, java.util.List.of()));
        mvc = MockMvcBuilders.standaloneSetup(
                        withValidation(new ClusterController(clusterFacade, operationService, sink, null, null)),
                        withValidation(new ClusterKubernetesController(kubeService, null, kindResolver)),
                        withValidation(new ClusterHelmReleaseController(chartService, operationService)),
                        withValidation(new OperationController(operationService)))
                .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
                .build();
    }

    /**
     * Spring AOP proxy 로 controller 를 감싸 {@link MethodValidationInterceptor} 를 적용.
     * 이렇게 하면 controller 의 {@code @Validated} 가 표준 MockMvc 환경에서도 path/query 검증을
     * 실제로 수행해 {@code ConstraintViolationException} 을 던진다.
     */
    @SuppressWarnings("unchecked")
    private static <T> T withValidation(T controller) {
        ProxyFactory pf = new ProxyFactory(controller);
        pf.addAdvice(new MethodValidationInterceptor());
        pf.setProxyTargetClass(true); // CGLIB — interface 없는 controller class 도 proxy
        return (T) pf.getProxy();
    }

    /** 길이 초과 (K8S_NAME_MAX=63) — annotation 안에서 String.repeat 불가하므로 별도 상수. */
    private static final String NAME_TOO_LONG = "a".repeat(64);

    private static final String KIND_TOO_LONG = "a".repeat(51);

    // =============== Cluster name (K8S_NAME_PATTERN) ===============

    @ParameterizedTest(name = "GET /v1/clusters/{0} → 400")
    @ValueSource(
            strings = {
                "BadCase", // 대문자 거부
                "-leadingHyphen", // - 로 시작 금지
                "trailing-", // - 로 끝 금지
                "with_underscore", // _ 금지
                "with.dot" // . 금지
            })
    void clusterName_invalidPathPattern_returns400(String badName) throws Exception {
        mvc.perform(get("/v1/clusters/" + badName)).andExpect(status().isBadRequest());
    }

    @Test
    void clusterName_tooLong_returns400() throws Exception {
        mvc.perform(get("/v1/clusters/" + NAME_TOO_LONG)).andExpect(status().isBadRequest());
    }

    @Test
    void clusterName_validRfc1123_returns2xx() throws Exception {
        when(clusterFacade.getOne(anyString())).thenReturn(null);
        mvc.perform(get("/v1/clusters/demo-aws-01")).andExpect(status().is2xxSuccessful());
    }

    // =============== K8s kind (K8S_KIND_PATTERN — 소문자만) ===============

    @ParameterizedTest(name = "GET /v1/clusters/c/namespaces/default/{0} → 400")
    @ValueSource(
            strings = {
                "Pods", // 대문자 거부
                "pod-with-hyphen", // hyphen 금지 (`^[a-z][a-z0-9]{0,49}$`)
                "123pods" // 숫자로 시작 금지
            })
    void k8sKind_invalidPathPattern_returns400(String badKind) throws Exception {
        mvc.perform(get("/v1/clusters/demo-aws-01/namespaces/default/" + badKind))
                .andExpect(status().isBadRequest());
    }

    @Test
    void k8sKind_tooLong_returns400() throws Exception {
        mvc.perform(get("/v1/clusters/demo-aws-01/namespaces/default/" + KIND_TOO_LONG))
                .andExpect(status().isBadRequest());
    }

    @Test
    void k8sKind_valid_returns2xx() throws Exception {
        when(kubeService.listResourcesPaginated(anyString(), any(), anyString(), anyInt(), any(), any()))
                .thenReturn(PagedKubeResourceResponse.builder()
                        .clusterName("demo")
                        .resourceType("pods")
                        .items(JsonNodeFactory.instance.arrayNode())
                        .returnedItemCount(0)
                        .build());
        mvc.perform(get("/v1/clusters/demo-aws-01/namespaces/default/pods")).andExpect(status().is2xxSuccessful());
    }

    // =============== Namespace path (NAMESPACE_PATTERN — name 또는 -/_all sentinel) ===============

    @ParameterizedTest(name = "GET /v1/clusters/c/namespaces/{0}/pods → 400")
    @ValueSource(
            strings = {
                "Default", // 대문자
                "ns_underscore", // underscore (sentinel `_all` 만 허용)
                "ns.dot", // dot 금지
                "-bad", // hyphen 만 sentinel
                "bad-" // trailing hyphen
            })
    void namespace_invalidPathPattern_returns400(String badNs) throws Exception {
        mvc.perform(get("/v1/clusters/demo-aws-01/namespaces/" + badNs + "/pods"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void namespace_tooLong_returns400() throws Exception {
        mvc.perform(get("/v1/clusters/demo-aws-01/namespaces/" + NAME_TOO_LONG + "/pods"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest(name = "GET /v1/clusters/c/namespaces/{0}/pods → 2xx (valid)")
    @ValueSource(strings = {"default", "kube-system", "web", "-", "_all"})
    void namespace_valid_returns2xx(String validNs) throws Exception {
        when(kubeService.listResourcesPaginated(anyString(), any(), anyString(), anyInt(), any(), any()))
                .thenReturn(PagedKubeResourceResponse.builder()
                        .clusterName("demo")
                        .resourceType("pods")
                        .items(JsonNodeFactory.instance.arrayNode())
                        .returnedItemCount(0)
                        .build());
        mvc.perform(get("/v1/clusters/demo-aws-01/namespaces/" + validNs + "/pods"))
                .andExpect(status().is2xxSuccessful());
    }

    // =============== Helm release name (K8S_NAME_PATTERN) ===============

    @ParameterizedTest(name = "DELETE /v1/clusters/c/helm-releases/{0} → 400")
    @ValueSource(strings = {"BadRelease", "release_under", "-bad", "bad-"})
    void helmReleaseName_invalidPathPattern_returns400(String badRel) throws Exception {
        mvc.perform(delete("/v1/clusters/demo-aws-01/helm-releases/" + badRel)).andExpect(status().isBadRequest());
    }

    // =============== Page size 범위 (1..500) ===============

    @ParameterizedTest(name = "pageSize={0} → 400")
    @ValueSource(ints = {0, 501, 1000, -1})
    void pageSize_outOfRange_returns400(int badSize) throws Exception {
        mvc.perform(get("/v1/clusters/demo-aws-01/namespaces/default/pods").param("pageSize", String.valueOf(badSize)))
                .andExpect(status().isBadRequest());
    }
}
