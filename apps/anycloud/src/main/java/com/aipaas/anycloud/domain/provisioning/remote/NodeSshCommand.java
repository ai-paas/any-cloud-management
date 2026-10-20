package com.aipaas.anycloud.domain.provisioning.remote;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 웹 터미널이 노드에 붙을 때 쓰는 ssh 명령.
 *
 * <p>대화형 셸이므로 원격 TTY 할당. 비대화형 실행과 달리 명령 미첨부. 첨부 시 해당 명령만
 * 실행하고 연결 종료.
 */
public final class NodeSshCommand {

    /** host 는 Pulumi outputs 유래. 옵션 형태 값의 ssh 인자 전달 금지. */
    private static final Pattern SAFE_HOST = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.\\-]*");

    private static final Pattern SAFE_USER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._\\-]*");

    private static final int DEFAULT_PORT = 22;

    private NodeSshCommand() {}

    /**
     * ssh 인자로 사용 가능한 host 만 통과.
     *
     * <p>{@code ProcessBuilder} 는 셸을 경유하지 않아 메타문자는 무해하나, {@code -} 로 시작하는
     * 값은 ssh 가 옵션으로 해석. {@code -oProxyCommand=...} 입력 시 임의 명령 실행.
     */
    public static String requireSafeHost(String host) {
        if (host == null || !SAFE_HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("사용할 수 없는 host: " + host);
        }
        return host;
    }

    public static List<String> build(String privateKeyPath, String sshUser, String host) {
        return build(privateKeyPath, sshUser, host, null, DEFAULT_PORT);
    }

    public static List<String> build(String privateKeyPath, String sshUser, String host, SshJump jump) {
        return build(privateKeyPath, sshUser, host, jump, DEFAULT_PORT);
    }

    /** 사설망 노드는 점프 호스트 경유. 프로비저닝과 동일 경로 사용. */
    public static List<String> build(String privateKeyPath, String sshUser, String host, SshJump jump, int port) {
        requireSafeHost(host);
        if (sshUser == null || !SAFE_USER.matcher(sshUser).matches()) {
            throw new IllegalArgumentException("사용할 수 없는 sshUser: " + sshUser);
        }
        List<String> cmd = new java.util.ArrayList<>(List.of(
                "ssh",
                // 대화형 셸이라 원격 TTY 필요. -t 단일 지정은 stdin 이 파이프일 때 미할당.
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
        if (port != DEFAULT_PORT) {
            cmd.add("-p");
            cmd.add(String.valueOf(port));
        }
        // -J 는 대상 앞에 배치. 대상 뒤 배치 시 ssh 가 원격 명령으로 해석.
        cmd.addAll(SshJumpOptions.args(jump));
        cmd.add(sshUser + "@" + host);
        return List.copyOf(cmd);
    }
}
