package com.aipaas.anycloud.domain.kube.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipaas.anycloud.common.error.exception.ClusterNotFoundException;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.agent.runtime.KubeResourceService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * circuit fallback 이 agent 장애를 성공으로 위장하지 않는지 확인.
 *
 * <p>null / 빈 배열을 돌려주면 "조회 결과 없음" 과 구분되지 않아 200 으로 나간다. 실제로 터널 뒤
 * 클러스터에서 agent 가 한 건도 응답하지 않는데 200 + data:null 이 나갔다.
 */
class KubeServiceImplFallbackTest extends AbstractUnitTest {

    private final KubeServiceImpl service = new KubeServiceImpl(
            new ObjectMapper(),
            Mockito.mock(KubeResourceService.class),
            Mockito.mock(KubeDegradedMetricsRecorder.class));

    private static final Throwable AGENT_DOWN = new IllegalStateException("Agent LIST_RESOURCES timeout after 20s");

    @Test
    void getResourceFallback_surfacesFailureInsteadOfNull() {
        assertThatThrownBy(() -> service.getResourceFallback("c1", "ns", "deployments", "app", AGENT_DOWN))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("getResource");
    }

    @Test
    void getResourcesFallback_surfacesFailureInsteadOfEmptyList() {
        assertThatThrownBy(() -> service.getResourcesFallback("c1", "ns", "pods", AGENT_DOWN))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("getResources");
    }

    @Test
    void fallbacks_rethrowClusterNotFoundUnchanged() {
        // 미존재 클러스터는 404. agent 불통(503)과 혼재 시 진단 불일치.
        ClusterNotFoundException notFound = new ClusterNotFoundException("c1");

        assertThatThrownBy(() -> service.getResourceFallback("c1", "ns", "deployments", "app", notFound))
                .isSameAs(notFound);
        assertThatThrownBy(() -> service.getResourcesFallback("c1", "ns", "pods", notFound))
                .isSameAs(notFound);
    }

    @Test
    void failureMessageNamesClusterAndOperation() {
        // 운영자가 로그 없이도 실패한 클러스터와 호출을 식별할 수 있어야 함.
        assertThatThrownBy(() -> service.getResourcesFallback("prod-01", "ns", "pods", AGENT_DOWN))
                .hasMessageContaining("prod-01");

        assertThat(AGENT_DOWN.getMessage()).contains("timeout");
    }
}
