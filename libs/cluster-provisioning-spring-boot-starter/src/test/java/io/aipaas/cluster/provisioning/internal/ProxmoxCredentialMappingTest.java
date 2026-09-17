package io.aipaas.cluster.provisioning.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Proxmox 자격증명 매핑 회귀 보호. */
class ProxmoxCredentialMappingTest {

    private Map<String, String> map(Map<String, String> env) {
        return CspCredentialPulumiConfigMapper.toPulumiConfig("proxmox", env);
    }

    private Map<String, String> baseEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("PROXMOX_VE_ENDPOINT", "https://pve1:8006/");
        env.put("PROXMOX_VE_API_TOKEN", "anycloud@pve!prov=uuid");
        return env;
    }

    @Test
    void sshUsernameDefaultsToRoot() {
        // API 토큰 인증에서는 provider 가 ssh.username 을 상속하지 못한다.
        assertThat(map(baseEnv())).containsEntry("proxmoxve:ssh.username", "root");
    }

    @Test
    void explicitSshUsernameWins() {
        Map<String, String> env = baseEnv();
        env.put("PROXMOX_VE_SSH_USERNAME", "anycloud-ssh");

        assertThat(map(env)).containsEntry("proxmoxve:ssh.username", "anycloud-ssh");
    }

    @Test
    void apiTokenExcludesUsernamePassword() {
        // 둘 다 넘기면 provider 가 거부한다.
        Map<String, String> env = baseEnv();
        env.put("PROXMOX_VE_USERNAME", "root@pam");
        env.put("PROXMOX_VE_PASSWORD", "secret");

        assertThat(map(env))
                .containsEntry("proxmoxve:apiToken", "anycloud@pve!prov=uuid")
                .doesNotContainKeys("proxmoxve:username", "proxmoxve:password");
    }

    @Test
    void fallsBackToUsernamePasswordWithoutToken() {
        Map<String, String> env = new HashMap<>();
        env.put("PROXMOX_VE_ENDPOINT", "https://pve1:8006/");
        env.put("PROXMOX_VE_USERNAME", "root@pam");
        env.put("PROXMOX_VE_PASSWORD", "secret");

        assertThat(map(env)).containsEntry("proxmoxve:username", "root@pam").doesNotContainKey("proxmoxve:apiToken");
    }

    @Test
    void nodeOverrideMustBeJsonArray() {
        // 값이 깨지면 SSH 가 엉뚱한 주소로 간다. 조용히 무시하면 원인 찾기가 오래 걸린다.
        Map<String, String> env = baseEnv();
        env.put("PROXMOX_VE_SSH_NODES", "{\"name\":\"pve1\"}");

        assertThatThrownBy(() -> map(env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON array");
    }

    @Test
    void nodeOverridePassesThrough() {
        Map<String, String> env = baseEnv();
        env.put("PROXMOX_VE_SSH_NODES", "[{\"name\":\"pve1\",\"address\":\"10.0.0.11\"}]");

        assertThat(map(env)).containsKey("proxmoxve:ssh.nodes");
    }

    @Test
    void credentialEnvNeverLeaksToProcess() {
        // 프로세스 env 로 새면 다른 스택 실행이 남의 자격증명을 쓴다.
        Map<String, String> stripped = CspCredentialPulumiConfigMapper.stripCspEnv(baseEnv());

        assertThat(stripped).doesNotContainKeys("PROXMOX_VE_ENDPOINT", "PROXMOX_VE_API_TOKEN");
    }
}
