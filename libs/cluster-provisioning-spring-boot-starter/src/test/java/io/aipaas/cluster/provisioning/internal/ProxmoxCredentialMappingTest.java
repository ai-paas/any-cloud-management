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
        // PVE 는 두 값을 따로 보여주지만 provider 는 한 줄로 받는다.
        assertThat(map(baseEnv()))
                .containsEntry("proxmoxve:apiToken", "aipaas@pve!provisioner=00000000-0000-0000-0000-000000000000");
    }

    @Test
    void surroundingWhitespaceIsDropped() {
        // 콘솔에서 복사하면 줄바꿈이나 공백이 딸려 온다. 그대로 이으면 401 로만 드러난다.
        Map<String, String> env = baseEnv();
        env.put("PROXMOX_VE_API_TOKEN_ID", " aipaas@pve!provisioner\n");
        env.put("PROXMOX_VE_API_TOKEN_SECRET", "00000000-0000-0000-0000-000000000000 ");

        assertThat(map(env))
                .containsEntry("proxmoxve:apiToken", "aipaas@pve!provisioner=00000000-0000-0000-0000-000000000000");
    }

    @Test
    void anIncompleteTokenProducesNoConfigAtAll() {
        /*
         * 한쪽만 채워 이어 붙이면 "aipaas@pve!provisioner=" 같은 값이 provider 로 넘어간다.
         * 형식은 맞아 보여서 설정 단계를 통과하고 첫 API 호출에서야 실패한다.
         */
        Map<String, String> env = baseEnv();
        env.remove("PROXMOX_VE_API_TOKEN_SECRET");

        assertThat(map(env)).doesNotContainKey("proxmoxve:apiToken").containsKey("proxmoxve:endpoint");
    }

    @Test
    void noUsernamePasswordPathRemains() {
        // 토큰 하나로 끝난다. 되살리면 자격증명 화면이 다시 배타적인 두 경로를 묻는다.
        Map<String, String> env = baseEnv();
        env.put("PROXMOX_VE_USERNAME", "root@pam");
        env.put("PROXMOX_VE_PASSWORD", "secret");

        assertThat(map(env)).doesNotContainKeys("proxmoxve:username", "proxmoxve:password");
    }

    @Test
    void noSshConfigIsEmitted() {
        /*
         * provider 가 PVE 호스트로 SSH 하는 경로는 snippet 업로드뿐이었다. 그 경로를 걷어낸 뒤에도
         * ssh.* 를 넘기면 자격증명 화면이 하이퍼바이저 호스트 접근 정보를 계속 요구한다.
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
        // 프로세스 env 로 새면 다른 스택 실행이 남의 자격증명을 쓴다.
        Map<String, String> stripped = CspCredentialPulumiConfigMapper.stripCspEnv(baseEnv());

        assertThat(stripped)
                .doesNotContainKeys("PROXMOX_VE_ENDPOINT", "PROXMOX_VE_API_TOKEN_ID", "PROXMOX_VE_API_TOKEN_SECRET");
    }
}
