package com.aipaas.anycloud.domain.provisioning.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 웹 터미널이 노드에 붙을 때 쓰는 ssh 명령.
 *
 * <p>대화형 셸이라 원격에 TTY 를 붙여야 한다. 비대화형 실행과 달리 명령을 붙이지 않는다 —
 * 붙이면 그것만 실행하고 끊긴다.
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
        // host 가 outputs 에서 오지만, 옵션처럼 생긴 값이 그대로 인자가 되면 안 된다.
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
}
