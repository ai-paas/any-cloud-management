package com.aipaas.anycloud.domain.provisioning.bootstrap;

import java.security.SecureRandom;

/** kubeadm bootstrap join token 생성기. */
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
