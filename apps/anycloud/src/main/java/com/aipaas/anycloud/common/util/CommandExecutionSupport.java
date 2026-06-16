package com.aipaas.anycloud.common.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class CommandExecutionSupport {

    /**
     * In-flight 외부 프로세스 추적 (graceful shutdown 용). Backend 종료 시 Pulumi 같은
     * long-running 프로세스를 SIGTERM 으로 정리하지 않으면 state backend 에 lock file 이 잔존.
     */
    private static final java.util.Set<Process> ACTIVE_PROCESSES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private CommandExecutionSupport() {}

    /**
     * 모든 in-flight 프로세스에 SIGTERM 전파 후 {@code grace} 동안 종료 대기 — 잔여는 SIGKILL.
     * Pulumi 는 SIGTERM 수신 시 checkpoint 저장 + lock 해제 후 종료하므로 stale lock 예방.
     * Spring {@code @PreDestroy} (graceful shutdown hook) 에서 호출.
     */
    public static void shutdownAll(Duration grace) {
        if (ACTIVE_PROCESSES.isEmpty()) {
            return;
        }
        // SIGTERM 일괄 전파.
        ACTIVE_PROCESSES.forEach(Process::destroy);
        long deadline = System.nanoTime() + grace.toNanos();
        for (Process p : ACTIVE_PROCESSES) {
            long remainingMs = Math.max(0, (deadline - System.nanoTime()) / 1_000_000);
            try {
                if (!p.waitFor(remainingMs, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        ACTIVE_PROCESSES.clear();
    }

    public static CommandExecutionResult execute(
            List<String> command, java.io.File workingDirectory, Map<String, String> environment, Duration timeout) {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory);
        }
        if (environment != null && !environment.isEmpty()) {
            builder.environment().putAll(environment);
        }

        final Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to execute command: " + String.join(" ", command), e);
        }
        ACTIVE_PROCESSES.add(process);
        try (var executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<String> stdoutFuture =
                    CompletableFuture.supplyAsync(() -> readStream(process.getInputStream()), executor);
            CompletableFuture<String> stderrFuture =
                    CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream()), executor);

            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Command timed out: " + String.join(" ", command));
            }

            return new CommandExecutionResult(process.exitValue(), stdoutFuture.get(), stderrFuture.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing command: " + String.join(" ", command), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to read command output: " + String.join(" ", command), e);
        } finally {
            ACTIVE_PROCESSES.remove(process);
        }
    }

    private static String readStream(InputStream stream) {
        try {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read process stream", e);
        }
    }

    /**
     * streaming variant. stdout 는 line 단위로 {@code stdoutLine}
     * consumer 로 push 되고, 동시에 전체 dump 도 {@link CommandExecutionResult#stdout()} 로 반환 (기존
     * caller 호환). consumer 가 던지는 예외는 swallow + log — 한 줄 처리 실패가 전체 명령을 중단시키지
     * 않는다.
     */
    public static CommandExecutionResult executeStreaming(
            List<String> command,
            java.io.File workingDirectory,
            Map<String, String> environment,
            Duration timeout,
            Consumer<String> stdoutLine) {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory);
        }
        if (environment != null && !environment.isEmpty()) {
            builder.environment().putAll(environment);
        }

        final Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to execute command: " + String.join(" ", command), e);
        }
        ACTIVE_PROCESSES.add(process);
        try (var executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> readStreamByLine(process.getInputStream(), stdoutLine), executor);
            CompletableFuture<String> stderrFuture =
                    CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream()), executor);

            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Command timed out: " + String.join(" ", command));
            }
            return new CommandExecutionResult(process.exitValue(), stdoutFuture.get(), stderrFuture.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing command: " + String.join(" ", command), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to read command output: " + String.join(" ", command), e);
        } finally {
            ACTIVE_PROCESSES.remove(process);
        }
    }

    /** stdout 을 line 단위로 consumer 에 push 하면서 전체 dump 도 누적 반환. */
    private static String readStreamByLine(InputStream stream, Consumer<String> lineConsumer) {
        StringBuilder all = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                all.append(line).append('\n');
                if (lineConsumer != null) {
                    try {
                        lineConsumer.accept(line);
                    } catch (RuntimeException ex) {
                        // consumer 예외는 swallow — 한 줄 처리 실패가 전체 명령을 중단시키면 안 됨.
                        // 호출자가 logger 를 가지므로 여기서 별도 log 없음.
                    }
                }
            }
            return all.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read process stream", e);
        }
    }

    public record CommandExecutionResult(int exitCode, String stdout, String stderr) {

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }
}
