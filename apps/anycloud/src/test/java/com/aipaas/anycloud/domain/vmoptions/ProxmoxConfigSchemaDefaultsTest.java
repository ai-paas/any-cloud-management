package com.aipaas.anycloud.domain.vmoptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.vmoptions.api.ProviderConfigKey;
import com.aipaas.anycloud.domain.vmoptions.internal.ProviderConfigSchemaServiceImpl;
import com.aipaas.anycloud.domain.vmoptions.properties.ProxmoxProvisioningDefaults;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 일괄 프로비저닝은 config-schema 의 defaultValue 만 보고 providerSpec 을 만든다.
 *
 * <p>Proxmox 는 nodeIps 가 없으면 차단되므로, 이 키들이 스키마에 없거나 기본값이 비면 일괄
 * 창에서 영영 쓸 수 없다. 실제로 그 상태였다.
 */
class ProxmoxConfigSchemaDefaultsTest {

    private static Optional<ProviderConfigKey> key(List<ProviderConfigKey> schema, String suffix) {
        return schema.stream()
                .filter(k -> k.key().equals("anycloud-k8s:providerSpec." + suffix))
                .findFirst();
    }

    @Test
    void proxmoxSchema_exposesStaticAddressingKeys() {
        var schema = new ProviderConfigSchemaServiceImpl(new ProxmoxProvisioningDefaults(
                        "10.0.0.10,10.0.0.11", "10.0.0.1", "nat.example.com", "2200,2201"))
                .getSchema("Proxmox");

        assertThat(key(schema, "nodeIps"))
                .isPresent()
                .get()
                .extracting(ProviderConfigKey::defaultValue)
                .isEqualTo("10.0.0.10,10.0.0.11");
        assertThat(key(schema, "gateway"))
                .isPresent()
                .get()
                .extracting(ProviderConfigKey::defaultValue)
                .isEqualTo("10.0.0.1");
        assertThat(key(schema, "sshHost"))
                .isPresent()
                .get()
                .extracting(ProviderConfigKey::defaultValue)
                .isEqualTo("nat.example.com");
        assertThat(key(schema, "sshPorts"))
                .isPresent()
                .get()
                .extracting(ProviderConfigKey::defaultValue)
                .isEqualTo("2200,2201");
    }

    /** 빈 문자열을 그대로 두면 일괄 창이 값이 있는 것으로 읽어 nodeIps 없이 생성에 들어간다. */
    @Test
    void blankDefaults_becomeNull() {
        var schema = new ProviderConfigSchemaServiceImpl(new ProxmoxProvisioningDefaults("", "  ", null, ""))
                .getSchema("Proxmox");

        assertThat(key(schema, "nodeIps"))
                .isPresent()
                .get()
                .extracting(ProviderConfigKey::defaultValue)
                .isNull();
        assertThat(key(schema, "gateway"))
                .isPresent()
                .get()
                .extracting(ProviderConfigKey::defaultValue)
                .isNull();
        assertThat(key(schema, "sshHost"))
                .isPresent()
                .get()
                .extracting(ProviderConfigKey::defaultValue)
                .isNull();
    }

    /** 기존 키가 사라지면 조용히 기본값으로 생성돼 엉뚱한 저장소에 디스크가 만들어진다. */
    @Test
    void existingProxmoxKeys_remain() {
        var schema = new ProviderConfigSchemaServiceImpl(new ProxmoxProvisioningDefaults(null, null, null, null))
                .getSchema("Proxmox");

        assertThat(key(schema, "nodeName")).isPresent();
        assertThat(key(schema, "datastoreId"))
                .isPresent()
                .get()
                .extracting(ProviderConfigKey::defaultValue)
                .isEqualTo("local-lvm");
        assertThat(key(schema, "networkBridge"))
                .isPresent()
                .get()
                .extracting(ProviderConfigKey::defaultValue)
                .isEqualTo("vmbr0");
    }
}
