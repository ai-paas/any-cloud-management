package com.aipaas.anycloud.domain.provisioning.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 웹 터미널이 노드에 붙을 때 쓰는 ssh 명령.
 *
 * <p>대화형 셸이므로 원격 TTY 할당 필요. 비대화형 실행과 달리 명령 미첨부. 첨부 시 해당
 * 명령만 실행하고 연결 종료.
 */
class NodeSshCommandTest extends AbstractUnitTest {

    @Test
    void allocatesATtySoTheShellIsInteractive() {
        List<String> cmd = NodeSshCommand.build("/tmp/key.pem", "ubuntu", "10.0.0.1");

        assertThat(cmd).contains("-tt");
    }

    @Test
    void doesNotAppendACommandSoTheLoginShellStays() {
        List<String> cmd = NodeSshCommand.build("/tmp/key.pem", "ubuntu", "10.0.0.1");

        assertThat(cmd.get(cmd.size() - 1)).isEqualTo("ubuntu@10.0.0.1");
    }

    @Test
    void skipsHostKeyPromptsThatWouldHangTheSession() {
        // 새 노드는 known_hosts 에 없다. 물어보면 브라우저에서는 답할 방법이 없어 멈춘다.
        List<String> cmd = NodeSshCommand.build("/tmp/key.pem", "ubuntu", "10.0.0.1");

        assertThat(cmd).contains("StrictHostKeyChecking=no");
        assertThat(cmd).contains("UserKnownHostsFile=/dev/null");
    }

    @Test
    void refusesPasswordAuthSoItCannotHangWaitingForInput() {
        List<String> cmd = NodeSshCommand.build("/tmp/key.pem", "ubuntu", "10.0.0.1");

        assertThat(cmd).contains("BatchMode=yes");
    }

    @Test
    void rejectsAHostThatCouldCarryExtraSshArguments() {
        // host 는 outputs 유래이나 옵션 형태 값의 인자 전달 금지.
        assertThatThrownBy(() -> NodeSshCommand.build("/tmp/key.pem", "ubuntu", "-oProxyCommand=touch /tmp/pwn"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankHostAndUser() {
        assertThatThrownBy(() -> NodeSshCommand.build("/tmp/key.pem", "ubuntu", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NodeSshCommand.build("/tmp/key.pem", " ", "10.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theHostCheckIsCallableBeforeBuilding() {
        // 호출부 선행 필터링 시 명령 구성 전에 차단.
        assertThat(NodeSshCommand.requireSafeHost("node-1.example.com")).isEqualTo("node-1.example.com");
    }

    @Test
    void theHostCheckRejectsAnythingThatSshWouldReadAsAnOption() {
        assertThatThrownBy(() -> NodeSshCommand.requireSafeHost("-oProxyCommand=touch /tmp/pwn"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NodeSshCommand.requireSafeHost("10.0.0.1 -oProxyCommand=x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NodeSshCommand.requireSafeHost(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
