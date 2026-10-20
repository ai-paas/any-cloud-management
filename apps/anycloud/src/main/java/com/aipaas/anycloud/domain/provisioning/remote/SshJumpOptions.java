package com.aipaas.anycloud.domain.provisioning.remote;

import java.util.List;
import java.util.regex.Pattern;

/** 점프 호스트를 ssh 인자로 변환. */
public final class SshJumpOptions {

    /** 자격증명 유래 값이라도 옵션 형태 값의 ssh 인자 전달 금지. */
    private static final Pattern SAFE_HOST = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.\\-]*");

    private static final Pattern SAFE_USER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._\\-]*");

    private SshJumpOptions() {}

    /**
     * 점프 호스트 부재 시 빈 목록.
     *
     * <p>{@code -J} 미사용. {@code -J} 는 내부 ssh 를 추가 실행하는데 해당 프로세스에 인증 옵션
     * 전달 수단 부재. 컨테이너에 {@code ~/.ssh/config} 가 없어 비밀번호 bastion 은 publickey 로
     * 시도하다 "Connection timed out during banner exchange" 로 정지.
     *
     * <p>host 존재, user 부재 시 예외. 무시하면 점프 설정이 적용된 것으로 인식한 상태에서 노드에
     * 직접 접속해 실패.
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
