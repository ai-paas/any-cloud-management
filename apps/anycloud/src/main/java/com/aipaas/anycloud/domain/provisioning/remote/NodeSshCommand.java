package com.aipaas.anycloud.domain.provisioning.remote;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 웹 터미널이 노드에 붙을 때 쓰는 ssh 명령.
 *
 * <p>대화형 셸이라 원격에 TTY 를 붙인다. 비대화형 실행과 달리 명령을 붙이지 않는다 — 붙이면
 * 그것만 실행하고 끊긴다.
 */
public final class NodeSshCommand {

    /** host 는 Pulumi outputs 에서 온다. 옵션처럼 생긴 값이 그대로 ssh 인자가 되면 안 된다. */
    private static final Pattern SAFE_HOST = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.\\-]*");

    private static final Pattern SAFE_USER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._\\-]*");

    private NodeSshCommand() {}

    public static List<String> build(String privateKeyPath, String sshUser, String host) {
        return build(privateKeyPath, sshUser, host, null);
    }

    /** 노드가 사설망이면 점프 호스트를 거친다. 프로비저닝과 같은 길을 쓴다. */
    public static List<String> build(String privateKeyPath, String sshUser, String host, SshJump jump) {
        if (host == null || !SAFE_HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("사용할 수 없는 host: " + host);
        }
        if (sshUser == null || !SAFE_USER.matcher(sshUser).matches()) {
            throw new IllegalArgumentException("사용할 수 없는 sshUser: " + sshUser);
        }
        List<String> cmd = new java.util.ArrayList<>(List.of(
                "ssh",
                // 대화형 셸이라 원격 TTY 가 필요하다. -t 하나로는 stdin 이 파이프일 때 붙지 않는다.
                "-tt",
                "-o",
                "StrictHostKeyChecking=no",
                "-o",
                "UserKnownHostsFile=/dev/null",
                // 새 노드는 known_hosts 에 없다. 물어보면 브라우저에서는 답할 방법이 없어 멈춘다.
                "-o",
                "BatchMode=yes",
                "-i",
                privateKeyPath));
        // -J 는 대상 앞에 와야 한다. 대상 뒤에 붙이면 ssh 가 원격 명령으로 읽는다.
        cmd.addAll(SshJumpOptions.args(jump));
        cmd.add(sshUser + "@" + host);
        return List.copyOf(cmd);
    }
}
