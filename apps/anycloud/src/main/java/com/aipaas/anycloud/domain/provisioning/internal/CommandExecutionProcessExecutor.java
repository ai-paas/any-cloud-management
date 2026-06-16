package com.aipaas.anycloud.service.provisioning.impl;

import com.aipaas.anycloud.common.util.CommandExecutionSupport;
import io.aipaas.cluster.provisioning.core.ProcessExecutor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * cluster-provisioning starter 의 {@link ProcessExecutor} 포트를 anycloud 의 {@link CommandExecutionSupport}
 * 로 연결하는 어댑터.
 *
 * <p>이 bean 이 존재하므로 starter 의 {@code DefaultProcessExecutor} 대신 anycloud 의 실행기가 쓰인다.
 * {@link CommandExecutionSupport} 는 in-flight 프로세스를 추적해 backend graceful shutdown 시 SIGTERM 을
 * 전파 — Pulumi 뿐 아니라 Helm / SSH 명령도 공유하는 앱 전역 실행 엔진이라 starter 밖(anycloud)에
 * 남는다. starter 는 Pulumi 오케스트레이션 정책만, 프로세스 생성 메커니즘은 host 가 책임지는 구조.
 */
@Component
public class CommandExecutionProcessExecutor implements ProcessExecutor {

    @Override
    public ExecResult execute(
            List<String> command, Path workingDirectory, Map<String, String> environment, Duration timeout) {
        CommandExecutionSupport.CommandExecutionResult result = CommandExecutionSupport.execute(
                command, workingDirectory == null ? null : workingDirectory.toFile(), environment, timeout);
        return new ExecResult(result.exitCode(), result.stdout(), result.stderr());
    }

    @Override
    public ExecResult executeStreaming(
            List<String> command,
            Path workingDirectory,
            Map<String, String> environment,
            Duration timeout,
            Consumer<String> lineConsumer) {
        CommandExecutionSupport.CommandExecutionResult result = CommandExecutionSupport.executeStreaming(
                command,
                workingDirectory == null ? null : workingDirectory.toFile(),
                environment,
                timeout,
                lineConsumer);
        return new ExecResult(result.exitCode(), result.stdout(), result.stderr());
    }
}
