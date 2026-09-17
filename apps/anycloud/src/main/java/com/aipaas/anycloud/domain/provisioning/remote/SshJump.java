package com.aipaas.anycloud.domain.provisioning.remote;

import java.util.Map;

/**
 * 그 클라우드의 노드로 가는 길목에 있는 점프 호스트 (bastion).
 *
 * <p>백엔드 전역 설정이 아니라 자격증명에 딸린다. OpenStack 설치마다 bastion 이 다르고 AWS, OCI 는
 * 공인 IP 라 필요 없다. bastion 비밀번호는 비밀이라 yaml 평문이 아니라 암호화되는 자격증명에 둔다.
 *
 * <p>로컬 터널({@code ssh -L})로는 안 된다 — 백엔드는 Pulumi outputs 의 노드 IP 로 직접 붙어서
 * 터널 포트를 모른다.
 */
public record SshJump(String host, int port, String user, String password) {

    public static final String KEY_HOST = "SSH_JUMP_HOST";
    public static final String KEY_PORT = "SSH_JUMP_PORT";
    public static final String KEY_USER = "SSH_JUMP_USER";
    public static final String KEY_PASSWORD = "SSH_JUMP_PASSWORD";

    private static final int DEFAULT_PORT = 22;

    /** 점프가 없으면 null. 별도 on/off 를 두면 host 를 넣고 켜는 것을 잊는다. */
    public static SshJump from(Map<String, String> credentials) {
        if (credentials == null) {
            return null;
        }
        String host = trimmed(credentials.get(KEY_HOST));
        if (host == null) {
            return null;
        }
        return new SshJump(
                host,
                port(credentials.get(KEY_PORT)),
                trimmed(credentials.get(KEY_USER)),
                trimmed(credentials.get(KEY_PASSWORD)));
    }

    public boolean usesPassword() {
        return password != null && !password.isBlank();
    }

    private static int port(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            // 포트를 못 읽었다고 접속을 막는 것보다 기본 포트로 시도하는 편이 낫다.
            return DEFAULT_PORT;
        }
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
