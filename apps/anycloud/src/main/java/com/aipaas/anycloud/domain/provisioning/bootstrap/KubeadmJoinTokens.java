package com.aipaas.anycloud.domain.provisioning.bootstrap;

import java.security.SecureRandom;

/**
 * kubeadm bootstrap join token 생성기.
 *
 * <p>형식: {@code [a-z0-9]{6}.[a-z0-9]{16}} (token-id "." token-secret) — kubeadm 이 요구하는
 * 고정 포맷. token-id 는 공개 식별자, token-secret 이 실질 인증 값.
 *
 * <p>과거엔 사용자 입력 또는 하드코딩 default ({@code abcdef.0123456789abcdef}) 를 사용 — 모든
 * cluster 가 같은 token 을 공유해 node 탈취 시 타 cluster join 이 가능했다. Backend 가 cluster
 * 생성 시점에 항상 새로 생성하고 사용자 입력은 무시.
 */
public final class KubeadmJoinTokens {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int ID_LENGTH = 6;
    private static final int SECRET_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private KubeadmJoinTokens() {}

    /** cluster 별 고유 random join token 생성. 호출마다 새 값. */
    public static String generate() {
        return randomLowerAlnum(ID_LENGTH) + "." + randomLowerAlnum(SECRET_LENGTH);
    }

    private static String randomLowerAlnum(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
