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
 * <p>Proxmox 는 nodeIps 부재 시 차단되므로, 이 키가 스키마에 없거나 기본값이 비면 일괄 생성
 * 화면에서 사용 불가. 실제 해당 상태로 존재.
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

    /** 빈 문자열 유지 시 일괄 생성 화면이 값 존재로 판정해 nodeIps 없이 생성 진입. */
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

    /** 기존 키 삭제 시 무음으로 기본값 생성돼 의도하지 않은 저장소에 디스크 생성. */
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
