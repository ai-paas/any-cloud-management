package io.aipaas.cluster.provisioning.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * OpenStack 매핑 회귀 보호.
 *
 * <p>authUrl / userName / password / tenantName 만 매핑하던 시절에는 Keystone v3 인증이
 * domain scope 누락으로 401 이었다. env 이름이 CSP_ENV_BLOCKLIST 로 제거되므로 provider 의
 * env 기본값도 대신 채워주지 못한다.
 */
class CspCredentialPulumiConfigMapperTest {

    private static Map<String, String> openrc() {
        Map<String, String> env = new HashMap<>();
        env.put("OS_AUTH_URL", "https://keystone.example.com:5000/v3");
        env.put("OS_USERNAME", "svc");
        env.put("OS_PASSWORD", "pw");
        env.put("OS_PROJECT_NAME", "proj");
        env.put("OS_PROJECT_ID", "a4a06ecb");
        env.put("OS_USER_DOMAIN_NAME", "Default");
        env.put("OS_PROJECT_DOMAIN_ID", "default");
        env.put("OS_REGION_NAME", "RegionOne");
        env.put("OS_INTERFACE", "public");
        return env;
    }

    @Test
    void openstack_mapsKeystoneV3DomainScope() {
        Map<String, String> config = CspCredentialPulumiConfigMapper.toPulumiConfig("openstack", openrc());

        assertThat(config)
                .containsEntry("openstack:userDomainName", "Default")
                .containsEntry("openstack:projectDomainId", "default");
    }

    @Test
    void openstack_mapsRegionAndEndpointType() {
        // provider 가 OS_REGION_NAME / OS_ENDPOINT_TYPE 을 env 에서 읽지만 blocklist 가 지운다.
        Map<String, String> config = CspCredentialPulumiConfigMapper.toPulumiConfig("openstack", openrc());

        assertThat(config)
                .containsEntry("openstack:region", "RegionOne")
                .containsEntry("openstack:endpointType", "public");
    }

    @Test
    void openstack_mapsCoreAuthFields() {
        Map<String, String> config = CspCredentialPulumiConfigMapper.toPulumiConfig("openstack", openrc());

        assertThat(config)
                .containsEntry("openstack:authUrl", "https://keystone.example.com:5000/v3")
                .containsEntry("openstack:userName", "svc")
                .containsEntry("openstack:password", "pw")
                .containsEntry("openstack:tenantName", "proj")
                .containsEntry("openstack:tenantId", "a4a06ecb");
    }

    @Test
    void openstack_prefersProjectOverLegacyTenantNames() {
        Map<String, String> env = openrc();
        env.put("OS_TENANT_NAME", "legacy");
        env.put("OS_TENANT_ID", "legacy-id");

        Map<String, String> config = CspCredentialPulumiConfigMapper.toPulumiConfig("openstack", env);

        assertThat(config)
                .containsEntry("openstack:tenantName", "proj")
                .containsEntry("openstack:tenantId", "a4a06ecb");
    }

    @Test
    void openstack_fallsBackToLegacyTenantNames() {
        Map<String, String> env = openrc();
        env.remove("OS_PROJECT_NAME");
        env.remove("OS_PROJECT_ID");
        env.put("OS_TENANT_NAME", "legacy");
        env.put("OS_TENANT_ID", "legacy-id");

        Map<String, String> config = CspCredentialPulumiConfigMapper.toPulumiConfig("openstack", env);

        assertThat(config)
                .containsEntry("openstack:tenantName", "legacy")
                .containsEntry("openstack:tenantId", "legacy-id");
    }

    @Test
    void openstack_normalizesInsecureToBooleanLiteral() {
        // clouds.yaml 의 verify:false 는 OS_INSECURE 로 넘어오는데 표기가 배포마다 다르다.
        for (String truthy : new String[] {"true", "1", "yes", "ON"}) {
            Map<String, String> env = openrc();
            env.put("OS_INSECURE", truthy);
            assertThat(CspCredentialPulumiConfigMapper.toPulumiConfig("openstack", env))
                    .as("%s", truthy)
                    .containsEntry("openstack:insecure", "true");
        }
        for (String falsy : new String[] {"false", "0", "no", "OFF"}) {
            Map<String, String> env = openrc();
            env.put("OS_INSECURE", falsy);
            assertThat(CspCredentialPulumiConfigMapper.toPulumiConfig("openstack", env))
                    .as("%s", falsy)
                    .containsEntry("openstack:insecure", "false");
        }
    }

    @Test
    void openstack_dropsUnparseableInsecure() {
        // 인식 못 하는 값을 그대로 넘기면 Pulumi 가 stack 전체를 거부한다.
        Map<String, String> env = openrc();
        env.put("OS_INSECURE", "maybe");

        assertThat(CspCredentialPulumiConfigMapper.toPulumiConfig("openstack", env))
                .doesNotContainKey("openstack:insecure");
    }

    @Test
    void openstack_omitsAbsentOptionalKeys() {
        Map<String, String> config = CspCredentialPulumiConfigMapper.toPulumiConfig("openstack", openrc());

        assertThat(config)
                .doesNotContainKey("openstack:userDomainId")
                .doesNotContainKey("openstack:domainName")
                .doesNotContainKey("openstack:cacertFile")
                .doesNotContainKey("openstack:insecure");
    }

    @Test
    void openstack_passesEndpointOverridesAsJson() {
        // 카탈로그가 광고하는 사설 주소로는 외부에서 못 닿는다 — override 없이는 provider 가
        // "dial tcp 192.168.10.10:9292: network is unreachable" 로 실패한다.
        Map<String, String> env = openrc();
        String overrides = "{\"image\":\"https://proxy.example.com:9292/v2/\"}";
        env.put("OS_ENDPOINT_OVERRIDES", overrides);

        assertThat(CspCredentialPulumiConfigMapper.toPulumiConfig("openstack", env))
                .containsEntry("openstack:endpointOverrides", overrides);
    }

    @Test
    void openstack_rejectsMalformedEndpointOverrides() {
        Map<String, String> env = openrc();
        env.put("OS_ENDPOINT_OVERRIDES", "image=https://proxy.example.com:9292");

        assertThatThrownBy(() -> CspCredentialPulumiConfigMapper.toPulumiConfig("openstack", env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openstack:endpointOverrides");
    }

    @Test
    void openstack_rejectsNonObjectEndpointOverrides() {
        // JSON 이긴 하나 map 이 아니면 provider 가 stack 전체를 거부한다.
        Map<String, String> env = openrc();
        env.put("OS_ENDPOINT_OVERRIDES", "[\"https://proxy.example.com:9292\"]");

        assertThatThrownBy(() -> CspCredentialPulumiConfigMapper.toPulumiConfig("openstack", env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");
    }

    @Test
    void stripCspEnv_removesEveryOpenstackKeyTheMapperReads() {
        // blocklist 가 새 키를 놓치면 Pulumi 하위 프로세스 env 로 자격증명이 새고,
        // provider 가 config 대신 env 를 집어 값이 엇갈린다.
        Map<String, String> env = openrc();
        env.put("OS_ENDPOINT_OVERRIDES", "{}");
        env.put("OS_INSECURE", "true");
        env.put("OS_CACERT", "/etc/ssl/ca.pem");
        env.put("OS_USER_ID", "uid");
        env.put("OS_TENANT_ID", "tid");
        env.put("OS_DOMAIN_NAME", "Default");
        env.put("PULUMI_BACKEND_URL", "s3://state");

        Map<String, String> stripped = CspCredentialPulumiConfigMapper.stripCspEnv(env);

        assertThat(stripped).containsOnlyKeys("PULUMI_BACKEND_URL");
    }

    @Test
    void unknownProvider_yieldsEmptyConfig() {
        assertThat(CspCredentialPulumiConfigMapper.toPulumiConfig("nonesuch", openrc()))
                .isEmpty();
    }
}
