package com.aipaas.anycloud.domain.provisioning.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 점프 호스트는 자격증명에 딸린다.
 *
 * <p>{@code -J} 는 내부 ssh 를 하나 더 띄우는데 그쪽에 인증 옵션을 실어 보낼 방법이 없다.
 * 컨테이너에는 {@code ~/.ssh/config} 가 없어 비밀번호 bastion 이면 publickey 로 시도하다 멈춘다 —
 * "Connection timed out during banner exchange" 로 실제로 막혔다. ProxyCommand 로 직접 짠다.
 */
class SshJumpOptionsTest extends AbstractUnitTest {

    private SshJump jump(Map<String, String> credentials) {
        return SshJump.from(credentials);
    }

    private String proxyCommand(SshJump j) {
        List<String> args = SshJumpOptions.args(j);
        int at = args.indexOf("-o");
        assertThat(at).as("-o 옵션이 없다").isGreaterThanOrEqualTo(0);
        return args.get(at + 1);
    }

    @Test
    void credentialWithoutAJumpHostMeansDirectAccess() {
        assertThat(jump(Map.of("OS_USERNAME", "u"))).isNull();
        assertThat(SshJump.from(null)).isNull();
        assertThat(SshJumpOptions.args(null)).isEmpty();
    }

    @Test
    void theJumpIsWiredAsAProxyCommandNotDashJ() {
        // -J 로는 내부 연결에 인증 옵션을 줄 수 없다.
        List<String> args =
                SshJumpOptions.args(jump(Map.of(SshJump.KEY_HOST, "bastion.example", SshJump.KEY_USER, "ops")));

        assertThat(args).doesNotContain("-J");
        assertThat(args.get(0)).isEqualTo("-o");
        assertThat(args.get(1)).startsWith("ProxyCommand=ssh -W %h:%p");
    }

    @Test
    void passwordBastionDoesNotTryPublicKeyFirst() {
        // publickey 로 시도하면 비밀번호를 물어볼 기회 없이 멈춘다.
        String cmd = proxyCommand(jump(
                Map.of(SshJump.KEY_HOST, "bastion.example", SshJump.KEY_USER, "ops", SshJump.KEY_PASSWORD, "secret")));

        assertThat(cmd).contains("PreferredAuthentications=password");
        assertThat(cmd).contains("PubkeyAuthentication=no");
    }

    @Test
    void keyBastionIsNotForcedIntoPasswordAuth() {
        String cmd = proxyCommand(jump(Map.of(SshJump.KEY_HOST, "bastion.example", SshJump.KEY_USER, "ops")));

        assertThat(cmd).doesNotContain("PreferredAuthentications=password");
    }

    @Test
    void theJumpDoesNotStopOnHostKeyPrompts() {
        // 새 bastion 은 known_hosts 에 없다. 물어보면 컨테이너에서는 답할 방법이 없어 멈춘다.
        String cmd = proxyCommand(jump(Map.of(SshJump.KEY_HOST, "bastion.example", SshJump.KEY_USER, "ops")));

        assertThat(cmd).contains("StrictHostKeyChecking=no");
        assertThat(cmd).contains("UserKnownHostsFile=/dev/null");
    }

    @Test
    void nonDefaultPortIsPassedThrough() {
        String cmd = proxyCommand(
                jump(Map.of(SshJump.KEY_HOST, "bastion.example", SshJump.KEY_PORT, "10022", SshJump.KEY_USER, "ops")));

        assertThat(cmd).contains("-p 10022");
        assertThat(cmd).endsWith("ops@bastion.example");
    }

    @Test
    void unreadablePortFallsBackInsteadOfBlockingAccess() {
        assertThat(jump(Map.of(SshJump.KEY_HOST, "b.example", SshJump.KEY_PORT, "열개", SshJump.KEY_USER, "ops"))
                        .port())
                .isEqualTo(22);
    }

    @Test
    void hostWithoutUserIsAConfigurationMistake() {
        // 조용히 무시하면 점프를 설정한 줄 알고 있는데 노드에 직접 붙어 실패한다.
        assertThatThrownBy(() -> SshJumpOptions.args(jump(Map.of(SshJump.KEY_HOST, "bastion.example"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hostThatLooksLikeAnOptionIsRejected() {
        assertThatThrownBy(() -> SshJumpOptions.args(
                        jump(Map.of(SshJump.KEY_HOST, "-oProxyCommand=touch /tmp/pwn", SshJump.KEY_USER, "ops"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void passwordAuthUsesAskpassSoItNeverWaitsForATerminal() {
        SshJump withPassword = jump(
                Map.of(SshJump.KEY_HOST, "bastion.example", SshJump.KEY_USER, "ops", SshJump.KEY_PASSWORD, "secret"));
        SshJump withoutPassword = jump(Map.of(SshJump.KEY_HOST, "bastion.example", SshJump.KEY_USER, "ops"));

        assertThat(SshJumpOptions.usesAskpass(withPassword)).isTrue();
        assertThat(SshJumpOptions.usesAskpass(withoutPassword)).isFalse();
    }

    @Test
    void jumpGoesBeforeTheTarget() {
        SshJump j = jump(Map.of(SshJump.KEY_HOST, "bastion.example", SshJump.KEY_USER, "ops"));

        List<String> cmd = NodeSshCommand.build("/tmp/key.pem", "ubuntu", "10.0.0.1", j);

        assertThat(cmd.get(cmd.size() - 1)).isEqualTo("ubuntu@10.0.0.1");
        assertThat(cmd).anyMatch(a -> a.startsWith("ProxyCommand="));
    }
}
