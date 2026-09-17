package io.aipaas.cluster.provisioning.program;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;

/** Kubernetes bootstrap token 생성/검증. RFC: {@code [a-z0-9]{6}.[a-z0-9]{16}}. */
public final class JoinTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    /** 명시 입력으로도 거부할 known weak sentinel — 운영 실수 차단. */
    public static final Set<String> WEAK = Set.of("abcdef.0123456789abcdef", "00000.0000000000000000");

    private JoinTokens() {}

    public static String generate() {
        byte[] idBytes = new byte[3];
        byte[] secretBytes = new byte[8];
        RANDOM.nextBytes(idBytes);
        RANDOM.nextBytes(secretBytes);
        return HEX.formatHex(idBytes) + "." + HEX.formatHex(secretBytes);
    }

    /** 빈 값 또는 weak sentinel 이면 새 token 생성, 아니면 그대로 반환. */
    public static String ensure(String current) {
        if (current == null || current.isBlank() || WEAK.contains(current)) {
            return generate();
        }
        return current;
    }
}
