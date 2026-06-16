package com.aipaas.anycloud.domain.addon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipaas.anycloud.domain.addon.internal.AddonSpecResolver;
import com.aipaas.anycloud.domain.addon.model.AddonSpec;
import com.aipaas.anycloud.domain.addon.model.AddonType;
import com.aipaas.anycloud.domain.addon.properties.AddonCatalogProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * N-13 — AddonSpecResolver: catalog merge + custom 모드 + validation.
 */
class AddonSpecResolverTest {

    private static final AddonCatalogProperties.Entry KUBE_PROMETHEUS = new AddonCatalogProperties.Entry(
            "kube-prometheus-stack",
            AddonType.MONITORING,
            "Prometheus + Grafana",
            "desc",
            "prometheus-community",
            "kube-prometheus-stack",
            "65.0.0",
            "monitoring",
            "kube-prometheus-stack",
            "https://prometheus-community.github.io/helm-charts",
            null,
            List.of(),
            List.of("monitoring"),
            null); // rbac null OK

    private final AddonCatalog catalog = new AddonCatalog(new AddonCatalogProperties(true, List.of(KUBE_PROMETHEUS)));
    private final AddonSpecResolver resolver = new AddonSpecResolver(catalog);

    @Test
    void catalogMode_usesCatalogDefaults() {
        AddonSpec spec = new AddonSpec(
                AddonType.MONITORING, "kube-prometheus-stack", null, null, null, null, null, null, null, true);

        ClusterAddonEntity e = resolver.resolve(spec, "cluster-1");

        assertThat(e.getClusterId()).isEqualTo("cluster-1");
        assertThat(e.getAddonType()).isEqualTo(AddonType.MONITORING);
        assertThat(e.getCatalogId()).isEqualTo("kube-prometheus-stack");
        assertThat(e.getChartRepo()).isEqualTo("prometheus-community");
        assertThat(e.getChartName()).isEqualTo("kube-prometheus-stack");
        assertThat(e.getChartVersion()).isEqualTo("65.0.0");
        assertThat(e.getNamespace()).isEqualTo("monitoring");
        assertThat(e.getReleaseName()).isEqualTo("kube-prometheus-stack");
        assertThat(e.getRepoUrl()).contains("prometheus-community.github.io");
        assertThat(e.getEnabled()).isTrue();
    }

    @Test
    void catalogMode_overrideAppliesPerField() {
        // chartVersion override
        AddonSpec spec = new AddonSpec(
                AddonType.MONITORING, "kube-prometheus-stack", null, null, null, null, "75.0.0", null, null, null);

        ClusterAddonEntity e = resolver.resolve(spec, "cluster-1");

        assertThat(e.getChartVersion()).isEqualTo("75.0.0");
        // 나머지는 catalog default 보존.
        assertThat(e.getChartName()).isEqualTo("kube-prometheus-stack");
        assertThat(e.getNamespace()).isEqualTo("monitoring");
    }

    @Test
    void customMode_requiresAllChartFields() {
        AddonSpec spec = new AddonSpec(
                AddonType.GENERIC,
                null,
                "my-redis",
                "default",
                "bitnami",
                "redis",
                "19.6.4",
                "https://charts.bitnami.com/bitnami",
                null,
                true);

        ClusterAddonEntity e = resolver.resolve(spec, "cluster-1");

        assertThat(e.getAddonType()).isEqualTo(AddonType.GENERIC);
        assertThat(e.getCatalogId()).isNull();
        assertThat(e.getChartName()).isEqualTo("redis");
        assertThat(e.getChartVersion()).isEqualTo("19.6.4");
    }

    @Test
    void customMode_missingChartFields_throws() {
        AddonSpec spec = new AddonSpec(AddonType.GENERIC, null, null, null, null, null, null, null, null, true);

        assertThatThrownBy(() -> resolver.resolve(spec, "cluster-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chartRepo/chartName/chartVersion");
    }

    @Test
    void missingType_throws() {
        AddonSpec spec = new AddonSpec(null, null, "x", "ns", "r", "c", "1.0", null, null, null);
        assertThatThrownBy(() -> resolver.resolve(spec, "cluster-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("addon type");
    }

    @Test
    void unknownCatalogId_throws() {
        // catalog 에 없는 catalogId 명시 시 fail-fast.
        AddonSpec spec =
                new AddonSpec(AddonType.MONITORING, "does-not-exist", null, null, null, null, null, null, null, true);
        assertThatThrownBy(() -> resolver.resolve(spec, "cluster-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown catalogId");
    }
}
