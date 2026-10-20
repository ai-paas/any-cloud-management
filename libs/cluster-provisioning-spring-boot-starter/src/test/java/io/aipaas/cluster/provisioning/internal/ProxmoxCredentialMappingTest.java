package io.aipaas.cluster.provisioning.internal;

import static org.assertj.core.api.Assertions.assertThat;

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
        env.put("PROXMOX_VE_API_TOKEN_ID", "aipaas@pve!provisioner");
        env.put("PROXMOX_VE_API_TOKEN_SECRET", "00000000-0000-0000-0000-000000000000");
        return env;
    }

    @Test
    void tokenIdAndSecretAreJoinedForTheProvider() {
        // PVE 는 두 값을 분리 표시하나 provider 는 한 줄로 수신.
        assertThat(map(baseEnv()))
                .containsEntry("proxmoxve:apiToken", "aipaas@pve!provisioner=00000000-0000-0000-0000-000000000000");
    }

    @Test
    void surroundingWhitespaceIsDropped() {
        // 콘솔 복사 시 줄바꿈이나 공백 혼입. 그대로 결합하면 401 로만 검출.
        Map<String, String> env = baseEnv();
        env.put("PROXMOX_VE_API_TOKEN_ID", " aipaas@pve!provisioner\n");
        env.put("PROXMOX_VE_API_TOKEN_SECRET", "00000000-0000-0000-0000-000000000000 ");

        assertThat(map(env))
                .containsEntry("proxmoxve:apiToken", "aipaas@pve!provisioner=00000000-0000-0000-0000-000000000000");
    }

    @Test
    void anIncompleteTokenProducesNoConfigAtAll() {
        /*
         * 한쪽만 채워 결합하면 "aipaas@pve!provisioner=" 형태 값이 provider 로 전달.
         * 형식이 유효해 보여 설정 단계를 통과하고 첫 API 호출 시점에 실패.
         */
        Map<String, String> env = baseEnv();
        env.remove("PROXMOX_VE_API_TOKEN_SECRET");

        assertThat(map(env)).doesNotContainKey("proxmoxve:apiToken").containsKey("proxmoxve:endpoint");
    }

    @Test
    void noUsernamePasswordPathRemains() {
        // 토큰 단일 항목으로 충분. 복원 시 자격증명 화면이 배타적인 두 경로를 다시 요구.
        Map<String, String> env = baseEnv();
        env.put("PROXMOX_VE_USERNAME", "root@pam");
        env.put("PROXMOX_VE_PASSWORD", "secret");

        assertThat(map(env)).doesNotContainKeys("proxmoxve:username", "proxmoxve:password");
    }

    @Test
    void noSshConfigIsEmitted() {
        /*
         * provider 의 PVE 호스트 SSH 경로는 snippet 업로드가 유일. 해당 경로 제거 후에도
         * ssh.* 를 전달하면 자격증명 화면이 하이퍼바이저 호스트 접근 정보를 계속 요구.
         */
        assertThat(map(baseEnv())).doesNotContainKeys("proxmoxve:ssh.username", "proxmoxve:ssh.nodes");
    }

    @Test
    void insecureIsPassedThroughForSelfSignedCertificates() {
        Map<String, String> env = baseEnv();
        env.put("PROXMOX_VE_INSECURE", "true");

        assertThat(map(env)).containsEntry("proxmoxve:insecure", "true");
    }

    @Test
    void credentialEnvNeverLeaksToProcess() {
        // 프로세스 env 로 누출되면 다른 스택 실행이 해당 자격증명을 사용.
        Map<String, String> stripped = CspCredentialPulumiConfigMapper.stripCspEnv(baseEnv());

        assertThat(stripped)
                .doesNotContainKeys("PROXMOX_VE_ENDPOINT", "PROXMOX_VE_API_TOKEN_ID", "PROXMOX_VE_API_TOKEN_SECRET");
    }
}
