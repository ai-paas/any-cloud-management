package com.aipaas.anycloud.domain.addon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipaas.anycloud.domain.addon.installer.AddonInstaller;
import com.aipaas.anycloud.domain.addon.installer.AddonInstallerRegistry;
import com.aipaas.anycloud.domain.addon.model.AddonType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** N-13 — AddonInstallerRegistry: type indexing + duplicate fail-fast + require API. */
class AddonInstallerRegistryTest {

    @Test
    void indexesByType_andResolvesByFindAndRequire() {
        AddonInstallerRegistry registry = new AddonInstallerRegistry(
                List.of(new FakeInstaller(AddonType.MONITORING), new FakeInstaller(AddonType.VELERO)));

        assertThat(registry.find(AddonType.MONITORING)).isNotNull();
        assertThat(registry.find(AddonType.VELERO)).isNotNull();
        assertThat(registry.find(AddonType.CERT_MANAGER)).isNull();

        assertThat(registry.require(AddonType.MONITORING).type()).isEqualTo(AddonType.MONITORING);
        assertThatThrownBy(() -> registry.require(AddonType.CERT_MANAGER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No AddonInstaller registered");
    }

    @Test
    void rejectsDuplicateTypeRegistration() {
        assertThatThrownBy(() -> new AddonInstallerRegistry(
                        List.of(new FakeInstaller(AddonType.MONITORING), new FakeInstaller(AddonType.MONITORING))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate AddonInstaller");
    }

    private record FakeInstaller(AddonType type) implements AddonInstaller {
        @Override
        public void install(ClusterAddonEntity addon) {
            // no-op
        }

        @Override
        public void uninstall(ClusterAddonEntity addon) {
            // no-op
        }
    }
}
