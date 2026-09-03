package com.aipaas.anycloud.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * AuditInterceptor 의 URI → resourceType / resourceId 휴리스틱 회귀 방지.
 * v1 path 들이 모두 정상 분류되고 Google AIP `:verb` custom method 가 올바르게
 * 분리되는지 확인.
 */
class AuditInterceptorTest extends AbstractUnitTest {

    private static MockHttpServletRequest req(String method, String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setRequestURI(uri);
        return r;
    }

    @ParameterizedTest(name = "resourceType: {0} → {1}")
    @CsvSource({
        // v1 cluster
        "/v1/clusters,                                              cluster",
        "/v1/clusters/demo-aws-01,                                  cluster",
        "/v1/clusters/demo-aws-01/operations,                       cluster",
        "/v1/clusters/demo-aws-01/connectivity-checks,              cluster",
        "/v1/clusters:importKubeconfig,                             cluster",
        // v1 helm-releases (sub-resource)
        "/v1/clusters/demo-aws-01/helm-releases,                    helmRelease",
        "/v1/clusters/demo-aws-01/helm-releases/ingress,            helmRelease",
        "/v1/clusters/demo-aws-01/helm-releases/ingress/revisions,  helmRelease",
        // v1 k8s sub-resource
        "/v1/clusters/demo-aws-01/namespaces/default/pods,          k8sResource",
        "/v1/clusters/demo-aws-01/namespaces/-/nodes,               k8sResource",
        "/v1/clusters/demo-aws-01/namespaces/_all/deployments/foo,  k8sResource",
        // v1 top-level
        "/v1/operations,                                             operation",
        "/v1/operations/op-001/cancel,                               operation",
        "/v1/cluster-validations,                                    clusterValidation",
        "/v1/helm-repos,                                             helmRepo",
        "/v1/helm-repos/bitnami,                                     helmRepo",
        "/v1/audit-logs,                                             auditLog",
        // Unknown / non-v1 prefix → null
        "/unknown,                                                   ",
        "/vm/clusters,                                               ",
        "/charts/x,                                                  "
    })
    void inferResourceType_classifiesV1AndLegacyPaths(String uri, String expected) {
        String actual = AuditInterceptor.inferResourceType(req("POST", uri));
        if (expected == null || expected.isBlank()) {
            assertThat(actual).isNull();
        } else {
            assertThat(actual).isEqualTo(expected);
        }
    }

    @ParameterizedTest(name = "resourceId: {0} → {1}")
    @CsvSource({
        // 단순 id (마지막 segment)
        "/v1/clusters/demo-aws-01,                                  demo-aws-01",
        "/v1/helm-repos/bitnami,                                    bitnami",
        "/v1/operations/op-001,                                     op-001",
        // III-58 colon-free custom-method 서브패스 — verb 한 단계 위 segment 가 id
        "/v1/operations/op-002/cancel,                              op-002",
        "/v1/clusters/demo-aws-01/addons/mon-01/retry,              mon-01",
        "/v1/clusters/demo-aws-01/addons/enqueue,                   addons",
        "/v1/admin/kind-cache/flush,                                kind-cache",
        "/v1/admin/clusters/demo-aws-01/kind-cache/flush,           kind-cache",
        "/v1/clusters/demo-aws-01/observability/alert-rules/install-all, alert-rules",
        // collection-level custom method (식별자 없음 — prev 가 collection marker)
        "/v1/clusters/importKubeconfig,                             ",
        // (legacy) 옛 :verb 콜론 경로 방어 — 콜론 앞부분만 사용
        "/v1/operations/op-legacy:cancel,                           op-legacy",
        // action-suffix verb (한 단계 위 segment 가 진짜 ID)
        "/v1/clusters/demo-aws-01/operations,                       demo-aws-01",
        "/v1/clusters/demo-aws-01/connectivity-checks,              demo-aws-01",
        "/v1/clusters/demo-aws-01/events,                           demo-aws-01",
        "/v1/clusters/demo-aws-01/helm-releases,                    demo-aws-01",
        "/v1/clusters/demo-aws-01/helm-releases/ingress/revisions,  ingress",
        "/v1/clusters/demo-aws-01/helm-releases/ingress/resources,  ingress",
        "/v1/clusters/demo-aws-01/helm-releases/ingress/operations, ingress",
        // k8s — 단건 (마지막 segment = 자원명)
        "/v1/clusters/demo-aws-01/namespaces/default/pods/nginx-abc, nginx-abc"
    })
    void inferResourceId_handlesVerbsAndCollectionMarkers(String uri, String expected) {
        String actual = AuditInterceptor.inferResourceId(req("POST", uri));
        if (expected == null || expected.isBlank()) {
            assertThat(actual).isNull();
        } else {
            assertThat(actual).isEqualTo(expected);
        }
    }

    @Test
    void inferResourceId_returnsNull_whenUriBlank() {
        assertThat(AuditInterceptor.inferResourceId(req("POST", ""))).isNull();
    }

    @Test
    void inferAction_fallback_whenHandlerNotHandlerMethod() {
        String action = AuditInterceptor.inferAction(new Object(), req("POST", "/v1/clusters"));
        assertThat(action).isEqualTo("POST /v1/clusters");
    }
}
