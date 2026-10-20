package com.aipaas.anycloud.domain.provisioning.remote;

import java.util.Map;

/**
 * 클라우드 노드 접근 경로의 점프 호스트 (bastion).
 *
 * <p>백엔드 전역 설정이 아닌 자격증명 종속. OpenStack 은 설치별 bastion 이 다르고 AWS, OCI 는
 * 공인 IP 라 불필요. bastion 비밀번호는 비밀값이므로 yaml 평문이 아닌 암호화 자격증명에 저장.
 *
 * <p>로컬 터널({@code ssh -L}) 사용 불가. 백엔드는 Pulumi outputs 의 노드 IP 로 직접 접속하므로
 * 터널 포트 미인지.
 */
public record SshJump(String host, int port, String user, String password) {

    public static final String KEY_HOST = "SSH_JUMP_HOST";
    public static final String KEY_PORT = "SSH_JUMP_PORT";
    public static final String KEY_USER = "SSH_JUMP_USER";
    public static final String KEY_PASSWORD = "SSH_JUMP_PASSWORD";

    private static final int DEFAULT_PORT = 22;

    /** 점프 호스트 부재 시 null. 별도 on/off 플래그 추가 시 host 입력 후 활성화 누락 발생. */
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
