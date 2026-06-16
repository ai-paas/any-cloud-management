package com.aipaas.anycloud.domain.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.agent.bootstrap.AgentApiManagedInstaller.AgentInstallResult;
import com.aipaas.anycloud.domain.kube.KubeService;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import io.aipaas.cluster.agent.identity.JwtRegistrationTokenService.IssuedToken;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AgentApiManagedInstaller end-to-end (token issue → manifest render → fabric8 apply).
 *
 * <p>Renderer 는 AgentChartRenderer (chart 를 source-of-truth 로 helm template) 로 대체됨.
 * 본 테스트는 mocked renderer 로 collaboration 만 검증 — chart rendering 자체 검증은
 * {@link AgentChartRendererTest} 의 통합 테스트에서.
 */
class AgentApiManagedInstallerTest extends AbstractUnitTest {

    private AgentBootstrapService bootstrapService;
    private AgentChartRenderer chartRenderer;
    private KubeService kubeService;
    private AgentApiManagedInstaller installer;

    @BeforeEach
    void setUp() {
        bootstrapService = Mockito.mock(AgentBootstrapService.class);
        chartRenderer = Mockito.mock(AgentChartRenderer.class);
        kubeService = Mockito.mock(KubeService.class);

        installer = new AgentApiManagedInstaller(
                bootstrapService,
                chartRenderer,
                kubeService,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                org.mockito.Mockito.mock(com.aipaas.anycloud.domain.agent.AgentProperties.class),
                org.mockito.Mockito.mock(com.aipaas.anycloud.domain.cluster.ClusterRepository.class));
        ReflectionTestUtils.setField(installer, "agentNamespace", "aipaas-system");
        installer.initMetrics();
    }

    @Test
    void install_happyPath_issuesTokenRendersAndApplies() {
        IssuedToken token = new IssuedToken("eyJ.fake.jwt", "jti-uuid-1", Instant.parse("2026-05-12T16:00:00Z"), 600);
        when(bootstrapService.issueRegistrationToken("demo-aws-01", "API_MANAGED"))
                .thenReturn(token);
        when(chartRenderer.render(eq("eyJ.fake.jwt"), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn("apiVersion: v1\nkind: Namespace\nmetadata:\n  name: aipaas-system\n");
        when(kubeService.applyResource(anyString(), anyString(), anyString())).thenReturn(null);

        AgentInstallResult result = installer.install("demo-aws-01");

        assertThat(result.clusterId()).isEqualTo("demo-aws-01");
        assertThat(result.registrationJti()).isEqualTo("jti-uuid-1");
        assertThat(result.manifestBytes()).isGreaterThan(0);

        verify(bootstrapService).issueRegistrationToken("demo-aws-01", "API_MANAGED");
        verify(chartRenderer).render(eq("eyJ.fake.jwt"), org.mockito.ArgumentMatchers.anyBoolean());
        verify(kubeService).applyResource(eq("demo-aws-01"), eq("aipaas-system"), contains("kind: Namespace"));
    }

    @Test
    void install_tokenIssueFails_doesNotCallApply() {
        when(bootstrapService.issueRegistrationToken(anyString(), anyString()))
                .thenThrow(new AgentBootstrapService.ClusterNotRegisteredException("not found"));

        assertThatThrownBy(() -> installer.install("ghost"))
                .isInstanceOf(AgentBootstrapService.ClusterNotRegisteredException.class);

        verify(chartRenderer, never()).render(anyString());
        verify(kubeService, never()).applyResource(anyString(), anyString(), anyString());
    }

    @Test
    void install_applyFails_propagates() {
        IssuedToken token = new IssuedToken("tok", "jti", Instant.now().plusSeconds(600), 600);
        when(bootstrapService.issueRegistrationToken(anyString(), anyString())).thenReturn(token);
        when(chartRenderer.render(anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn("manifest");
        when(kubeService.applyResource(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("apiserver down"));

        assertThatThrownBy(() -> installer.install("demo-aws-01"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("apiserver down");

        verify(kubeService, times(1)).applyResource(anyString(), anyString(), anyString());
    }

    @Test
    void install_installModeIsApiManaged() {
        // Token 발급 시 install_mode 가 정확히 "API_MANAGED" 인지 — claim 값으로 backend 추적용.
        IssuedToken token = new IssuedToken("tok", "jti", Instant.now().plusSeconds(600), 600);
        when(bootstrapService.issueRegistrationToken(eq("demo-aws-01"), eq("API_MANAGED")))
                .thenReturn(token);
        when(chartRenderer.render(anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn("manifest");

        installer.install("demo-aws-01");

        verify(bootstrapService).issueRegistrationToken(eq("demo-aws-01"), eq("API_MANAGED"));
    }
}
