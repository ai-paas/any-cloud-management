package io.aipaas.cluster.provisioning.service;

import io.aipaas.cluster.provisioning.core.ProcessExecutor;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * {@link ProcessExecutor} 의 기본 구현 — {@link ProcessBuilder} 기반.
 *
 * <p>host 가 자체 {@code ProcessExecutor} bean 을 등록하지 않을 때 starter autoconfig 가 제공하는
 * fallback. in-flight 프로세스 추적이나 graceful shutdown(SIGTERM) 기능은 없으므로, backend 종료 시
 * Pulumi lock 정리가 필요한 production host(anycloud 등)는 자체 실행기를 주입해 override 한다.
 */
public final class DefaultProcessExecutor implements ProcessExecutor {

	@Override
	public ExecResult execute(List<String> command, Path workingDirectory,
			Map<String, String> environment, Duration timeout) {
		return run(command, workingDirectory, environment, timeout, null);
	}

	@Override
	public ExecResult executeStreaming(List<String> command, Path workingDirectory,
			Map<String, String> environment, Duration timeout, Consumer<String> lineConsumer) {
		return run(command, workingDirectory, environment, timeout, lineConsumer);
	}

	private ExecResult run(List<String> command, Path workingDirectory,
			Map<String, String> environment, Duration timeout, Consumer<String> lineConsumer) {
		ProcessBuilder builder = new ProcessBuilder(command);
		if (workingDirectory != null) {
			builder.directory(workingDirectory.toFile());
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
		try (var executor = Executors.newFixedThreadPool(2)) {
			CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(
					() -> lineConsumer == null
							? readStream(process.getInputStream())
							: readStreamByLine(process.getInputStream(), lineConsumer),
					executor);
			CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(
					() -> readStream(process.getErrorStream()), executor);

			boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				throw new IllegalStateException("Command timed out: " + String.join(" ", command));
			}
			return new ExecResult(process.exitValue(), stdoutFuture.get(), stderrFuture.get());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while executing command: " + String.join(" ", command), e);
		} catch (ExecutionException e) {
			throw new IllegalStateException("Failed to read command output: " + String.join(" ", command), e);
		}
	}

	private static String readStream(InputStream stream) {
		try {
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read process stream", e);
		}
	}

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
					}
				}
			}
			return all.toString();
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read process stream", e);
		}
	}
}
