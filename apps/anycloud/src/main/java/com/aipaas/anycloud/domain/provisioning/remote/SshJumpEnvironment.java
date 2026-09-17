package com.aipaas.anycloud.domain.provisioning.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 점프 호스트 인증에 필요한 환경.
 *
 * <p>비밀번호 bastion 은 프롬프트를 띄우는데 컨테이너에는 터미널이 없어 그대로 멈춘다.
 * {@code SSH_ASKPASS} 로 넘기고 {@code SSH_ASKPASS_REQUIRE=force} 로 터미널이 없어도 쓰게 한다
 * (OpenSSH 8.4+).
 *
 * <p>비밀번호를 인자로 넘기지 않는다 — 인자는 같은 호스트의 다른 프로세스에서 보인다.
 */
public record SshJumpEnvironment(Map<String, String> env, Path askpassScript) implements AutoCloseable {

    private static final SshJumpEnvironment EMPTY = new SshJumpEnvironment(Map.of(), null);

    public static SshJumpEnvironment none() {
        return EMPTY;
    }

    /**
     * @param runtimeDir askpass 스크립트를 둘 자리. 프로세스가 끝나면 지운다
     */
    public static SshJumpEnvironment prepare(SshJump jump, Path runtimeDir) throws IOException {
        if (!SshJumpOptions.usesAskpass(jump)) {
            return none();
        }
        Files.createDirectories(runtimeDir);
        Path script = Files.createTempFile(runtimeDir, "askpass-", ".sh");
        // 비밀번호를 스크립트 본문에 두면 파일에 남는다. 환경변수로 받아 출력만 한다.
        Files.writeString(script, "#!/bin/sh\nprintf '%s' \"$ANYCLOUD_JUMP_PASSWORD\"\n", StandardCharsets.UTF_8);
        script.toFile().setReadable(false, false);
        script.toFile().setReadable(true, true);
        script.toFile().setExecutable(true, true);

        Map<String, String> env = new HashMap<>();
        env.put("SSH_ASKPASS", script.toString());
        env.put("SSH_ASKPASS_REQUIRE", "force");
        env.put("ANYCLOUD_JUMP_PASSWORD", jump.password());
        // DISPLAY 가 없으면 예전 OpenSSH 는 askpass 를 쓰지 않는다. REQUIRE=force 와 함께 둔다.
        env.put("DISPLAY", "none:0");
        return new SshJumpEnvironment(Map.copyOf(env), script);
    }

    @Override
    public void close() {
        if (askpassScript == null) {
            return;
        }
        try {
            Files.deleteIfExists(askpassScript);
        } catch (IOException ignored) {
            // 남아도 다음 기동에서 덮어쓴다.
        }
    }
}
