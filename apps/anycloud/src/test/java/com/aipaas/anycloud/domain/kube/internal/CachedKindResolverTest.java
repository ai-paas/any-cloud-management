package com.aipaas.anycloud.domain.kube.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import io.aipaas.cluster.agent.runtime.KubeResourceService;
import io.aipaas.cluster.agent.runtime.ResolvedResource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * {@link CachedKindResolver} 단위 테스트.
 *
 * <h2>커버 시나리오</h2>
 * <ol>
 *   <li>cache miss → agent 호출 1회 후 반환</li>
 *   <li>cache hit → agent 추가 호출 없음 (동일 cluster + kind)</li>
 *   <li>shortname 정규화 — 대소문자 다른 입력 동일 entry hit</li>
 *   <li>cluster 별 isolation — 같은 kind 라도 cluster 가 다르면 별도 entry</li>
 *   <li>invalidate(cluster) → 해당 cluster 만 flush, 다른 cluster 는 유지</li>
 *   <li>invalidateAll() → 전체 flush</li>
 *   <li>agent 예외 → hardcoded fallback (namespaced=false for cluster-scoped)</li>
 * </ol>
 */
class CachedKindResolverTest extends AbstractUnitTest {

    @Mock
    private KubeResourceService kubeResourceService;

    private CachedKindResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CachedKindResolver(kubeResourceService);
    }

    private static ResolvedResource pods() {
        return new ResolvedResource("pods", "pod", "Pod", "", "v1", true, List.of("po"));
    }

    private static ResolvedResource nodes() {
        return new ResolvedResource("nodes", "node", "Node", "", "v1", false, List.of("no"));
    }

    @Test
    void resolve_cacheMiss_callsAgent() {
        when(kubeResourceService.resolveResource("c1", "pods")).thenReturn(pods());

        ResolvedResource r = resolver.resolve("c1", "pods");

        assertThat(r.plural()).isEqualTo("pods");
        assertThat(r.namespaced()).isTrue();
        verify(kubeResourceService, times(1)).resolveResource("c1", "pods");
    }

    @Test
    void resolve_cacheHit_doesNotCallAgentAgain() {
        when(kubeResourceService.resolveResource("c1", "pods")).thenReturn(pods());

        resolver.resolve("c1", "pods");
        resolver.resolve("c1", "pods");
        resolver.resolve("c1", "pods");

        verify(kubeResourceService, times(1)).resolveResource("c1", "pods");
        verifyNoMoreInteractions(kubeResourceService);
    }

    @Test
    void resolve_caseInsensitive_sameEntry() {
        when(kubeResourceService.resolveResource(eq("c1"), any())).thenReturn(pods());

        resolver.resolve("c1", "pods");
        resolver.resolve("c1", "PODS");
        resolver.resolve("c1", "Pods");

        // 1회만 agent 호출 — 대소문자 정규화로 동일 cache key.
        verify(kubeResourceService, times(1)).resolveResource(eq("c1"), any());
    }

    @Test
    void resolve_differentClusters_isolated() {
        when(kubeResourceService.resolveResource("c1", "pods")).thenReturn(pods());
        when(kubeResourceService.resolveResource("c2", "pods")).thenReturn(pods());

        resolver.resolve("c1", "pods");
        resolver.resolve("c2", "pods");
        resolver.resolve("c1", "pods"); // c1 hit
        resolver.resolve("c2", "pods"); // c2 hit

        verify(kubeResourceService, times(1)).resolveResource("c1", "pods");
        verify(kubeResourceService, times(1)).resolveResource("c2", "pods");
    }

    @Test
    void invalidate_singleCluster_onlyAffectedClusterFlushed() {
        when(kubeResourceService.resolveResource("c1", "pods")).thenReturn(pods());
        when(kubeResourceService.resolveResource("c2", "pods")).thenReturn(pods());

        resolver.resolve("c1", "pods"); // miss → agent
        resolver.resolve("c2", "pods"); // miss → agent
        resolver.invalidate("c1");

        resolver.resolve("c1", "pods"); // miss again (flushed) → agent
        resolver.resolve("c2", "pods"); // still hit (not flushed)

        verify(kubeResourceService, times(2)).resolveResource("c1", "pods");
        verify(kubeResourceService, times(1)).resolveResource("c2", "pods");
    }

    @Test
    void invalidateAll_flushesEveryCluster() {
        when(kubeResourceService.resolveResource("c1", "pods")).thenReturn(pods());
        when(kubeResourceService.resolveResource("c2", "pods")).thenReturn(pods());

        resolver.resolve("c1", "pods");
        resolver.resolve("c2", "pods");
        resolver.invalidateAll();

        resolver.resolve("c1", "pods");
        resolver.resolve("c2", "pods");

        verify(kubeResourceService, times(2)).resolveResource("c1", "pods");
        verify(kubeResourceService, times(2)).resolveResource("c2", "pods");
    }

    @Test
    void resolve_agentThrows_fallbackToHardcodedSet() {
        when(kubeResourceService.resolveResource("c1", "nodes")).thenThrow(new RuntimeException("agent unavailable"));

        ResolvedResource r = resolver.resolve("c1", "nodes");

        assertThat(r).isNotNull();
        assertThat(r.plural()).isEqualTo("nodes");
        // nodes ∈ K8sKinds.CLUSTER_SCOPED → namespaced=false.
        assertThat(r.namespaced()).isFalse();
    }

    @Test
    void resolve_agentThrows_unknownKind_defaultsToNamespacedTrue() {
        when(kubeResourceService.resolveResource("c1", "deployments"))
                .thenThrow(new RuntimeException("agent unavailable"));

        ResolvedResource r = resolver.resolve("c1", "deployments");

        assertThat(r).isNotNull();
        // deployments ∉ K8sKinds.CLUSTER_SCOPED → fallback 은 namespaced=true 가정.
        assertThat(r.namespaced()).isTrue();
        assertThat(r.plural()).isEqualTo("deployments");
    }

    @Test
    void resolve_clusterScopedFromAgent_namespacedFalse() {
        when(kubeResourceService.resolveResource("c1", "nodes")).thenReturn(nodes());

        ResolvedResource r = resolver.resolve("c1", "nodes");

        assertThat(r.namespaced()).isFalse();
        assertThat(r.plural()).isEqualTo("nodes");
    }

    @Test
    void resolve_blankInputs_returnsNullWithoutAgentCall() {
        assertThat(resolver.resolve("c1", "")).isNull();
        assertThat(resolver.resolve("c1", null)).isNull();
        assertThat(resolver.resolve("", "pods")).isNull();
        assertThat(resolver.resolve(null, "pods")).isNull();
        verifyNoMoreInteractions(kubeResourceService);
    }
}
