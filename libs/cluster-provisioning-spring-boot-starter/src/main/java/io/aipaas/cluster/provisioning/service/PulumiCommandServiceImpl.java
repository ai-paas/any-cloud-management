package io.aipaas.cluster.provisioning.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aipaas.cluster.provisioning.core.ProcessExecutor;
import io.aipaas.cluster.provisioning.core.ProvisionEvent;
import io.aipaas.cluster.provisioning.core.PulumiCommandResult;
import io.aipaas.cluster.provisioning.core.PulumiExecutionConfig;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link PulumiCommandService} 의 기본 구현 — Pulumi CLI 오케스트레이션 로직.
 *
 * <p>명령 구성, 환경 변수 병합, 민감 인자 마스킹, {@code --json} 이벤트 스트리밍 파싱을 담당하고, 실제
 * OS 프로세스 생성은 {@link ProcessExecutor} 포트로, 설정은 {@link PulumiExecutionConfig} 포트로 위임한다.
 * 두 포트의 host 구현이 없으면 starter autoconfig 가 기본값을 제공한다. host(anycloud 등)가 자신의
 * 프로세스 실행기(graceful shutdown 포함)와 config 를 주입하면 그것을 사용한다.
 *
 * <p>{@code @Bulkhead("pulumi")}: 동시 Pulumi CLI 프로세스 수 제한. resilience4j 가 classpath 에 있으면
 * 적용되고, 없으면(다른 host) annotation 은 무시된다.
 */
@Slf4j
@RequiredArgsConstructor
public class PulumiCommandServiceImpl implements PulumiCommandService {

	private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

	private final PulumiExecutionConfig config;
	private final ObjectMapper objectMapper;
	private final ProcessExecutor processExecutor;
	private final ProvisionEventBus eventBus;

	@Override
	public PulumiCommandResult selectOrCreateStack(String stackName) {
		return selectOrCreateStack(stackName, Map.of());
	}

	@Override
	public PulumiCommandResult selectOrCreateStack(String stackName, Map<String, String> environmentOverrides) {
		PulumiCommandResult select = run(List.of("stack", "select", stackName), DEFAULT_TIMEOUT, environmentOverrides);
		if (select.isSuccess() || !config.isAutoCreateStack()) {
			return select;
		}
		List<String> initArgs = new ArrayList<>(List.of("stack", "init", stackName));
		String secretsProvider = config.getSecretsProvider();
		if (secretsProvider != null && !secretsProvider.isBlank()) {
			initArgs.add("--secrets-provider");
			initArgs.add(secretsProvider);
		}
		return run(initArgs, DEFAULT_TIMEOUT, environmentOverrides);
	}

	@Override
	public PulumiCommandResult selectStack(String stackName) {
		return selectStack(stackName, Map.of());
	}

	@Override
	public PulumiCommandResult selectStack(String stackName, Map<String, String> environmentOverrides) {
		return run(List.of("stack", "select", stackName), DEFAULT_TIMEOUT, environmentOverrides);
	}

	@Override
	public PulumiCommandResult setConfig(String key, String value, boolean secret) {
		return setConfig(key, value, secret, Map.of());
	}

	@Override
	public PulumiCommandResult setConfig(String key, String value, boolean secret,
			Map<String, String> environmentOverrides) {
		List<String> args = new ArrayList<>(List.of("config", "set"));
		if (secret) {
			args.add("--secret");
		}
		args.add(key);
		args.add(value);
		return run(args, Duration.ofMinutes(2), environmentOverrides);
	}

	@Override
	public PulumiCommandResult up() {
		return up(Map.of());
	}

	@Override
	public PulumiCommandResult up(Map<String, String> environmentOverrides) {
		return run(List.of("up", "--yes", "--skip-preview"), DEFAULT_TIMEOUT, environmentOverrides);
	}

	@Override
	public PulumiCommandResult destroy() {
		return destroy(Map.of());
	}

	@Override
	public PulumiCommandResult destroy(Map<String, String> environmentOverrides) {
		return run(List.of("destroy", "--yes", "--skip-preview"), DEFAULT_TIMEOUT, environmentOverrides);
	}

	@Override
	public PulumiCommandResult upWithEvents(String operationId, Map<String, String> environmentOverrides) {
		return runStreaming(
				List.of("up", "--yes", "--skip-preview", "--json"),
				DEFAULT_TIMEOUT, environmentOverrides, operationId);
	}

	@Override
	public PulumiCommandResult destroyWithEvents(String operationId, Map<String, String> environmentOverrides) {
		return runStreaming(
				List.of("destroy", "--yes", "--skip-preview", "--json"),
				DEFAULT_TIMEOUT, environmentOverrides, operationId);
	}

	@Override
	public PulumiCommandResult removeStack(String stackName) {
		return removeStack(stackName, Map.of());
	}

	@Override
	public PulumiCommandResult removeStack(String stackName, Map<String, String> environmentOverrides) {
		return run(List.of("stack", "rm", stackName, "--yes"), Duration.ofMinutes(2), environmentOverrides);
	}

	@Override
	public PulumiCommandResult removeStackForce(String stackName, Map<String, String> environmentOverrides) {
		// --force: state 안에 resource 가 남아있어도 강제 삭제. orphan stack cleanup 에만 사용.
		return run(List.of("stack", "rm", stackName, "--force", "--yes"), Duration.ofMinutes(2), environmentOverrides);
	}

	@Override
	public PulumiCommandResult cancel(String stackName, Map<String, String> environmentOverrides) {
		// `pulumi cancel [<stack-name>] --yes` — positional stack arg.
		return run(List.of("cancel", stackName, "--yes"), Duration.ofMinutes(2), environmentOverrides);
	}

	@Override
	public Map<String, Object> stackOutputs() {
		return stackOutputs(false);
	}

	@Override
	public Map<String, Object> stackOutputs(boolean showSecrets) {
		return stackOutputs(showSecrets, Map.of());
	}

	@Override
	public Map<String, Object> stackOutputs(boolean showSecrets, Map<String, String> environmentOverrides) {
		List<String> args = new ArrayList<>(List.of("stack", "output", "--json"));
		if (showSecrets) {
			args.add("--show-secrets");
		}
		PulumiCommandResult result = run(args, Duration.ofMinutes(2), environmentOverrides);
		if (!result.isSuccess()) {
			throw new IllegalStateException("Failed to read Pulumi outputs: " + result.getStderr());
		}
		try {
			return objectMapper.readValue(result.getStdout(), new TypeReference<>() {});
		} catch (IOException e) {
			throw new IllegalStateException("Failed to parse Pulumi output JSON", e);
		}
	}

	@Override
	public PulumiCommandResult run(List<String> args, Duration timeout) {
		return run(args, timeout, Map.of());
	}

	@Override
	@Bulkhead(name = "pulumi")
	// Bulkhead: 동시 Pulumi CLI 프로세스 수 제한 (config: max-concurrent-calls).
	// pulumi up/destroy 는 1 프로세스당 CPU/MEM 비용이 크므로 무제한 동시 실행은 머신 고갈을 초래.
	// 큐잉 방식으로 max-wait-duration 동안 대기 후에도 슬롯이 안 비면 BulkheadFullException.
	public PulumiCommandResult run(List<String> args, Duration timeout, Map<String, String> environmentOverrides) {
		List<String> command = buildCommand(args);
		Map<String, String> environment = buildEnvironment(environmentOverrides);

		// Sensitive-flag 다음 토큰은 마스킹 (Pulumi 의 --config-passphrase 등 inline secret 보호).
		log.info("Executing Pulumi command: {}", maskSensitiveArgs(command));

		ProcessExecutor.ExecResult executionResult = processExecutor.execute(
				command, config.resolveProjectDir(), environment, timeout);

		PulumiCommandResult result = toResult(executionResult);
		if (!result.isSuccess()) {
			log.error("Pulumi command failed. exitCode={}, stderr={}", result.getExitCode(), result.getStderr());
		}
		return result;
	}

	@Override
	public Path projectDir() {
		return config.resolveProjectDir();
	}

	/**
	 * Pulumi --json 모드 + streaming. {@code pulumi up --json} 은 각 stdout line 이 engine event JSON
	 * 한 개. 본 method 가 line 별 파싱 → ProvisionEvent 변환 → eventBus.publish. 동시에 전체 stdout 도
	 * collect 해 기존 caller 호환 결과 반환.
	 *
	 * <p>--json 모드는 sensitive 한 plain log 가 stderr 로 가므로 stderr 누설 위험은 기존과 동일.
	 */
	@Bulkhead(name = "pulumi")
	PulumiCommandResult runStreaming(List<String> args, Duration timeout,
			Map<String, String> environmentOverrides, String operationId) {
		List<String> command = buildCommand(args);
		Map<String, String> environment = buildEnvironment(environmentOverrides);

		log.info("Executing Pulumi streaming command: {}", maskSensitiveArgs(command));

		ProcessExecutor.ExecResult executionResult = processExecutor.executeStreaming(
				command, config.resolveProjectDir(), environment, timeout,
				line -> handleStreamingLine(operationId, line));

		PulumiCommandResult result = toResult(executionResult);
		if (!result.isSuccess()) {
			log.error("Pulumi streaming command failed. exitCode={}, stderr={}",
					result.getExitCode(), result.getStderr());
		}
		return result;
	}

	/** 바이너리 + 인자 명령 배열 구성. */
	private List<String> buildCommand(List<String> args) {
		List<String> command = new ArrayList<>();
		command.add(config.getBinaryPath());
		command.addAll(args);
		return command;
	}

	/** 기본 환경 + override + passphrase/backend URL 병합 (시스템 환경 병합은 ProcessExecutor 담당). */
	private Map<String, String> buildEnvironment(Map<String, String> environmentOverrides) {
		Map<String, String> environment = new HashMap<>();
		if (config.getEnvironment() != null) {
			environment.putAll(config.getEnvironment());
		}
		environment.putAll(environmentOverrides);
		if (config.getPassphrase() != null && !config.getPassphrase().isBlank()) {
			environment.put("PULUMI_CONFIG_PASSPHRASE", config.getPassphrase());
		}
		if (config.getBackendUrl() != null && !config.getBackendUrl().isBlank()) {
			environment.put("PULUMI_BACKEND_URL", config.getBackendUrl());
		}
		return environment;
	}

	private static PulumiCommandResult toResult(ProcessExecutor.ExecResult executionResult) {
		return PulumiCommandResult.builder()
				.exitCode(executionResult.exitCode())
				.stdout(executionResult.stdout())
				.stderr(executionResult.stderr())
				.build();
	}

	/** Pulumi engine event 한 line 처리 — JSON parse → ProvisionEvent → bus.publish. */
	private void handleStreamingLine(String operationId, String line) {
		if (line == null || line.isBlank()) {
			return;
		}
		// Pulumi --json 은 각 line 이 완전한 JSON object. parse 실패는 무시 (warning/prelude line 등
		// JSON 이 아닌 line 이 섞일 수 있음 — Pulumi 버전에 따라 다름).
		try {
			Map<String, Object> json = objectMapper.readValue(line, new TypeReference<>() {});
			ProvisionEvent event = ProvisionEvent.fromPulumiJson(operationId, json);
			eventBus.publish(event);
		} catch (IOException e) {
			// non-JSON line — skip. trace 수준 (prelude/banner 가 매번 찍히면 noise).
			if (log.isTraceEnabled()) {
				log.trace("non-JSON pulumi line skipped: {}", line);
			}
		}
	}

	/**
	 * Pulumi CLI 의 sensitive-flag 다음 토큰을 마스킹. PULUMI_CONFIG_PASSPHRASE 같은 secret 는
	 * environment 로 전달되므로 command array 에 직접 나타나지 않지만, 운영자가 inline 으로 사용할
	 * 경우 (예: {@code --config-passphrase=<secret>} 또는 {@code --config-passphrase <secret>}) 대비.
	 */
	private static String maskSensitiveArgs(List<String> command) {
		List<String> sensitiveFlags = List.of(
				"--config-passphrase", "--passphrase", "--secrets-provider", "--token");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < command.size(); i++) {
			String arg = command.get(i);
			if (sb.length() > 0) sb.append(' ');
			// `--flag=value` 형태 — value 부분만 마스킹.
			int eq = arg.indexOf('=');
			if (eq > 0 && sensitiveFlags.contains(arg.substring(0, eq))) {
				sb.append(arg, 0, eq + 1).append("***");
				continue;
			}
			// `--flag value` 형태 — value 부분만 마스킹.
			if (sensitiveFlags.contains(arg) && i + 1 < command.size()) {
				sb.append(arg).append(" ***");
				i++; // skip masked token
				continue;
			}
			sb.append(arg);
		}
		return sb.toString();
	}
}
