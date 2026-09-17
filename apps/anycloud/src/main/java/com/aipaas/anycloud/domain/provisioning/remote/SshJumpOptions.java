package com.aipaas.anycloud.domain.provisioning.remote;

import java.util.List;
import java.util.regex.Pattern;

/** 점프 호스트를 ssh 인자로 옮긴다. */
public final class SshJumpOptions {

    /** 자격증명에서 온 값이지만 옵션처럼 생긴 값이 그대로 ssh 인자가 되면 안 된다. */
    private static final Pattern SAFE_HOST = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.\\-]*");

    private static final Pattern SAFE_USER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._\\-]*");

    private SshJumpOptions() {}

    /**
     * 점프가 없으면 빈 목록.
     *
     * <p>{@code -J} 를 쓰지 않는다. {@code -J} 는 내부 ssh 를 하나 더 띄우는데 그쪽에 인증 옵션을
     * 실어 보낼 방법이 없다. 컨테이너에는 {@code ~/.ssh/config} 가 없어 비밀번호 bastion 이면
     * publickey 로 시도하다 "Connection timed out during banner exchange" 로 멈춘다.
     *
     * <p>host 는 있는데 user 가 없으면 예외다 — 조용히 무시하면 점프를 설정한 줄 알고 있는데
     * 노드에 직접 붙어 실패한다.
     */
    public static List<String> args(SshJump jump) {
        if (jump == null) {
            return List.of();
        }
        if (jump.host() == null || !SAFE_HOST.matcher(jump.host()).matches()) {
            throw new IllegalStateException(SshJump.KEY_HOST + " 가 사용할 수 없는 값입니다: " + jump.host());
        }
        if (jump.user() == null || !SAFE_USER.matcher(jump.user()).matches()) {
            throw new IllegalStateException(SshJump.KEY_USER + " 가 비었거나 사용할 수 없는 값입니다: " + jump.user());
        }

        StringBuilder proxy = new StringBuilder("ProxyCommand=ssh -W %h:%p");
        if (jump.port() != 22) {
            proxy.append(" -p ").append(jump.port());
        }
        // 새 bastion 은 known_hosts 에 없다. 물어보면 컨테이너에서는 답할 방법이 없어 멈춘다.
        proxy.append(" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null");
        if (jump.usesPassword()) {
            // publickey 로 먼저 시도하면 비밀번호를 물어볼 기회 없이 멈춘다.
            proxy.append(" -o PreferredAuthentications=password -o PubkeyAuthentication=no");
        }
        proxy.append(' ').append(jump.user()).append('@').append(jump.host());

        return List.of("-o", proxy.toString());
    }

    /** 비밀번호 bastion 이면 askpass 로 넘긴다. 컨테이너에는 터미널이 없어 프롬프트가 뜨면 멈춘다. */
    public static boolean usesAskpass(SshJump jump) {
        return jump != null && jump.usesPassword();
    }
}
