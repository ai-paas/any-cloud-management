package com.aipaas.anycloud.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;

/** {@link AgentProperties.Helm} 의 default 와 chart 좌표 조합. */
class AgentPropertiesTest extends AbstractUnitTest {

    @Test
    void helm_defaults_pointToPublishedOciChart() {
        // publish-charts.yml 이 올리는 좌표와 같아야 함. 어긋나면 안내 명령이 404.
        AgentProperties.Helm helm = new AgentProperties.Helm(null, null, null);

        assertThat(helm.ociRegistry()).isEqualTo("oci://ghcr.io/ai-paas/charts");
        assertThat(helm.chartName()).isEqualTo("cluster-agent");
        assertThat(helm.chartVersion()).isEqualTo("0.1.0");
        assertThat(helm.ociChartRef()).isEqualTo("oci://ghcr.io/ai-paas/charts/cluster-agent");
    }

    @Test
    void ociChartRef_trailingSlash_doesNotDoubleSeparator() {
        // registry 를 env 로 덮어쓸 때 흔한 입력. helm 은 // 를 좌표 오류로 처리.
        AgentProperties.Helm helm = new AgentProperties.Helm("oci://registry.local/charts/", "cluster-agent", "0.2.0");

        assertThat(helm.ociChartRef()).isEqualTo("oci://registry.local/charts/cluster-agent");
    }
}
