package com.aipaas.anycloud.domain.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipaas.anycloud.domain.agent.bootstrap.AgentChartRenderer.ImageRef;
import com.aipaas.anycloud.domain.chart.internal.HelmCommandExecutor;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AgentChartRenderer 회귀 보호.
 *
 * <p>두 트랙:
 * <ul>
 *   <li>{@code parseImageRef} 같은 순수 함수 — 일반 유닛 테스트.</li>
 *   <li>실제 helm template 호출 — helm CLI + 빌드 시 복사된 agent-chart 가 필요해 통합 테스트.
 *       {@code -Dchart.integration=true} system property 가 있을 때만 실행 (CI 에서 helm 설치
 *       후 활성화 권장). 로컬에서 helm 이 있으면 그냥 켜고 돌리면 됨.</li>
 * </ul>
 */
class AgentChartRendererTest extends AbstractUnitTest {

    /* ---------- parseImageRef (pure) ---------- */

    @Test
    void parseImageRef_simpleRepoTag() {
        ImageRef r = AgentChartRenderer.parseImageRef("consine2c/cluster-agent:dev");
        assertThat(r.repository()).isEqualTo("consine2c/cluster-agent");
        assertThat(r.tag()).isEqualTo("dev");
    }

    @Test
    void parseImageRef_registryWithPort_safeSplit() {
        ImageRef r = AgentChartRenderer.parseImageRef("registry.local:5000/cluster-agent:dev");
        assertThat(r.repository()).isEqualTo("registry.local:5000/cluster-agent");
        assertThat(r.tag()).isEqualTo("dev");
    }

    @Test
    void parseImageRef_noTag_defaultsLatest() {
        ImageRef r = AgentChartRenderer.parseImageRef("ghcr.io/aipaas/cluster-agent");
        assertThat(r.repository()).isEqualTo("ghcr.io/aipaas/cluster-agent");
        assertThat(r.tag()).isEqualTo("latest");
    }

    @Test
    void parseImageRef_blank_fallback() {
        assertThat(AgentChartRenderer.parseImageRef(null).repository()).isEqualTo("aipaas/cluster-agent");
        assertThat(AgentChartRenderer.parseImageRef("").tag()).isEqualTo("dev");
    }

    /* ---------- render() — helm template integration ---------- */

    private AgentChartRenderer renderer;

    @BeforeEach
    void setUp() throws Exception {
        // 통합 테스트 — helm CLI + bundled chart 둘 다 필요. system property 없으면 skip.
        if (!Boolean.getBoolean("chart.integration")) {
            return;
        }
        HelmCommandExecutor helm = new HelmCommandExecutor();
        renderer = new AgentChartRenderer(helm);
        ReflectionTestUtils.setField(renderer, "backendEndpoint", "backend.example.com:9090");
        ReflectionTestUtils.setField(renderer, "agentImage", "consine2c/cluster-agent:dev");
        ReflectionTestUtils.setField(renderer, "agentNamespace", "aipaas-system");
        ReflectionTestUtils.setField(renderer, "imagePullPolicy", "IfNotPresent");
        // @PostConstruct 를 수동 호출 (Spring context 없으므로).
        ReflectionTestUtils.invokeMethod(renderer, "extractChart");
        assertThat(renderer.getChartDir()).isNotNull();
        assertThat(Files.isRegularFile(renderer.getChartDir().resolve("Chart.yaml")))
                .isTrue();
    }

    @Test
    @EnabledIfSystemProperty(named = "chart.integration", matches = "true")
    void render_emitsManifestWithToken() {
        String manifest = renderer.render("eyJhbGc.fake.jwt");

        // 핵심 자원이 렌더링됨. Namespace 는 conditional (agent.ns ≠ release.ns 시만) — chart 의
        // 의도된 동작.
        assertThat(manifest).contains("kind: ServiceAccount");
        assertThat(manifest).contains("kind: ClusterRole");
        assertThat(manifest).contains("kind: ClusterRoleBinding");
        assertThat(manifest).contains("kind: ConfigMap");
        assertThat(manifest).contains("kind: Secret");
        assertThat(manifest).contains("kind: Deployment");

        // token + endpoint 가 inline.
        assertThat(manifest).contains("registration-token: \"eyJhbGc.fake.jwt\"");
        assertThat(manifest).contains("backend.example.com:9090");

        // image override 가 적용됨 (chart default 가 아닌 우리가 주입한 값).
        assertThat(manifest).contains("image: \"consine2c/cluster-agent:dev\"");

        // allowlist 의 wildcard 가 quote 처리되어 YAML alias 로 잘못 해석되는 것 방지.
        assertThat(manifest).contains("- \"*\"");

        // installer SA 의 core-read cross-binding 존재.
        assertThat(manifest).contains("aipaas-agent-installer-core-read");
    }

    @Test
    @EnabledIfSystemProperty(named = "chart.integration", matches = "true")
    void render_emptyToken_throws() {
        assertThatThrownBy(() -> renderer.render(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("registrationToken");
        assertThatThrownBy(() -> renderer.render(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @EnabledIfSystemProperty(named = "chart.integration", matches = "true")
    void extractChart_putsChartYamlOnDisk() {
        Path chart = renderer.getChartDir();
        assertThat(Files.isDirectory(chart)).isTrue();
        assertThat(Files.isRegularFile(chart.resolve("Chart.yaml"))).isTrue();
        assertThat(Files.isDirectory(chart.resolve("templates"))).isTrue();
        assertThat(Files.isRegularFile(chart.resolve("templates").resolve("deployment.yaml")))
                .isTrue();
    }
}
