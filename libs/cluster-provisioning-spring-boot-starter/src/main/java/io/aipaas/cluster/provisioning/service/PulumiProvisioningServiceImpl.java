package io.aipaas.cluster.provisioning.service;

import io.aipaas.cluster.provisioning.core.ProvisioningExecutionException;
import io.aipaas.cluster.provisioning.core.ProvisioningOutput;
import io.aipaas.cluster.provisioning.core.ProvisioningRequest;
import io.aipaas.cluster.provisioning.core.PulumiExecutionConfig;
import io.aipaas.cluster.provisioning.core.PulumiPreviewResult;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PulumiProvisioningService} 기본 구현 — Pulumi stack 준비 + config 적용 + up/preview/destroy
 * 오케스트레이션. CLI 실행은 {@link PulumiCommandService}, 실행 config 는 {@link PulumiExecutionConfig}
 * (binary/projectDir/stackPrefix/passphrase 등), stale lock 복구는 {@link PulumiStaleLockGuard} 에 위임.
 *
 * <p>host(anycloud)가 자체 구현을 등록하지 않으면 starter autoconfig 가 이 bean 을 제공한다.
 */
@Slf4j
@RequiredArgsConstructor
public class PulumiProvisioningServiceImpl implements PulumiProvisioningService {

	private static final java.time.Duration PREVIEW_TIMEOUT = java.time.Duration.ofMinutes(10);

	private final PulumiExecutionConfig config;
	private final PulumiCommandService pulumiCommandService;
	private final ProvisioningOutputMapper provisioningOutputMapper;
	private final PulumiStaleLockGuard staleLockGuard;

	@Override
	public String buildStackName(ProvisioningRequest request) {
		return String.join("-",
				config.getStackPrefix(),
				valueOrDefault(request.getProvider(), "unknown"),
				valueOrDefault(request.getEnvironment(), "dev"),
				valueOrDefault(request.getClusterName(), "cluster"));
	}

	/**
	 * Pulumi CLI 호출은 5–30 분 걸릴 수 있으므로 caller 의 트랜잭션을 그대로 가져가면
	 * DB connection 이 잡혀 pool 고갈 발생. {@link Propagation#NOT_SUPPORTED} 로 caller TX 와
	 * 분리해 Pulumi 실행 중에는 connection 을 점유하지 않는다.
	 */
	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public Map<String, Object> provision(ProvisioningRequest request) {
		assertEnabled();
		// CSP credential 의 env (AWS_ACCESS_KEY_ID 등) 를 Pulumi binary 의 process env 에
		// 넣으면 state backend (RustFS) 자격증명을 덮어쓰는 충돌 발생. CSP 자격증명은 stack config
		// (aws:accessKey 등) 로 분리해서 Pulumi default provider 에 전달, process env 는 state
		// backend 용 host env (compose 의 AWS_ACCESS_KEY_ID=anycloud) 만 남기도록 strip.
		Map<String, String> rawCredEnv = request.credentialEnvironmentOrEmpty();
		Map<String, String> cspStackConfig = CspCredentialPulumiConfigMapper.toPulumiConfig(
				request.getProvider(), rawCredEnv);
		Map<String, String> environmentOverrides = CspCredentialPulumiConfigMapper.stripCspEnv(rawCredEnv);

		String stackName = buildStackName(request);
		var stackResult = pulumiCommandService.selectOrCreateStack(stackName, environmentOverrides);
		if (!stackResult.isSuccess()) {
			throw new IllegalStateException("Failed to select or create stack: " + stackResult.getStderr());
		}

		applyStackConfig(request, cspStackConfig, environmentOverrides);

		var upResult = staleLockGuard.run(stackName, environmentOverrides,
				() -> pulumiCommandService.up(environmentOverrides));
		if (!upResult.isSuccess()) {
			throw new IllegalStateException("Failed to provision cluster: " + upResult.getStderr());
		}

		return pulumiCommandService.stackOutputs(true, environmentOverrides);
	}

	/**
	 * Cluster create 사전 미리보기. provision 과 동일한 stack/config 준비 후
	 * {@code pulumi preview --json} 만 실행 — CSP 자원은 만들지 않는다.
	 *
	 * <p>신규 cluster (stack 미존재) 는 preview 용으로 stack 을 임시 생성하고 끝나면 제거 —
	 * resource 0 개 상태라 {@code stack rm --force} 가 안전. 기존 stack 은 그대로 두고 diff 만
	 * 반환 (drift / 변경 예정 확인).
	 */
	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public PulumiPreviewResult preview(ProvisioningRequest request) {
		assertEnabled();
		Map<String, String> rawCredEnv = request.credentialEnvironmentOrEmpty();
		Map<String, String> cspStackConfig = CspCredentialPulumiConfigMapper.toPulumiConfig(
				request.getProvider(), rawCredEnv);
		Map<String, String> environmentOverrides = CspCredentialPulumiConfigMapper.stripCspEnv(rawCredEnv);

		String stackName = buildStackName(request);
		boolean existedBefore = pulumiCommandService.selectStack(stackName, environmentOverrides).isSuccess();
		if (!existedBefore) {
			var createResult = pulumiCommandService.selectOrCreateStack(stackName, environmentOverrides);
			if (!createResult.isSuccess()) {
				throw new IllegalStateException("Failed to create preview stack: " + createResult.getStderr());
			}
		}
		try {
			applyStackConfig(request, cspStackConfig, environmentOverrides);

			var previewResult = staleLockGuard.run(stackName, environmentOverrides,
					() -> pulumiCommandService.run(
							java.util.List.of("preview", "--json"), PREVIEW_TIMEOUT, environmentOverrides));
			if (!previewResult.isSuccess()) {
				// --json 모드는 diagnostics 가 stdout JSON 에 들어가고 stderr 가 빌 수 있음.
				// 입력 오류가 아닌 외부 시스템 실패 — host 는 ProvisioningExecutionException
				// 을 UPSTREAM_FAILED(502) 로 매핑 (STATE_CONFLICT/INVALID_INPUT 오해 방지).
				throw new ProvisioningExecutionException(
						"Pulumi preview failed: "
								+ extractFailureDetail(previewResult.getStderr(), previewResult.getStdout()));
			}
			return PulumiPreviewParser.parse(stackName, existedBefore, previewResult.getStdout());
		} finally {
			// 임시 생성한 stack 은 흔적 없이 정리 — resource 가 없으므로 force rm 안전. 실패해도
			// preview 결과엔 영향 없음 (orphan-state admin endpoint 로 후속 정리 가능).
			if (!existedBefore) {
				var rm = pulumiCommandService.removeStackForce(stackName, environmentOverrides);
				if (!rm.isSuccess()) {
					log.warn("Failed to remove ephemeral preview stack {}: {}", stackName, rm.getStderr());
				}
			}
		}
	}

	/**
	 * Stack config 일괄 적용 — CSP 자격증명 (표준 provider config key, 항상 --secret) +
	 * anycloud-k8s 요청 config. provision / preview 가 공유.
	 */
	private void applyStackConfig(ProvisioningRequest request, Map<String, String> cspStackConfig,
			Map<String, String> environmentOverrides) {
		Map<String, String> config = new LinkedHashMap<>(request.configOrEmpty());
		config.putIfAbsent("anycloud-k8s:provider", valueOrDefault(request.getProvider(), "aws"));
		config.putIfAbsent("anycloud-k8s:name", request.getClusterName());
		config.putIfAbsent("anycloud-k8s:environment", valueOrDefault(request.getEnvironment(), "dev"));
		config.putIfAbsent("anycloud-k8s:region", request.getRegion());
		// CSP 자격증명 — 표준 provider config key (aws:accessKey 등) 로 set. Pulumi default
		// provider 가 자동 인식. 모두 --secret 으로 저장 → Pulumi state 의 passphrase 로 암호화.
		for (Map.Entry<String, String> e : cspStackConfig.entrySet()) {
			var r = pulumiCommandService.setConfig(e.getKey(), e.getValue(), true, environmentOverrides);
			if (!r.isSuccess()) {
				throw new IllegalStateException("Failed to set Pulumi config " + e.getKey() + ": " + r.getStderr());
			}
		}
		for (Map.Entry<String, String> entry : config.entrySet()) {
			if (entry.getValue() == null || entry.getValue().isBlank()) {
				continue;
			}
			boolean secret = isSecretKey(entry.getKey());
			var configResult = pulumiCommandService.setConfig(entry.getKey(), entry.getValue(), secret, environmentOverrides);
			if (!configResult.isSuccess()) {
				throw new IllegalStateException("Failed to set Pulumi config " + entry.getKey() + ": " + configResult.getStderr());
			}
		}
	}

	@Override
	public Map<String, Object> stackOutputs(String stackName, boolean showSecrets) {
		return stackOutputs(stackName, showSecrets, Map.of());
	}

	@Override
	public Map<String, Object> stackOutputs(String stackName, boolean showSecrets,
			Map<String, String> environmentOverrides) {
		assertEnabled();
		// Caller (BOOTSTRAP/VERIFY step, retry registration) 가 raw CSP env 를 전달해도
		// state backend (RustFS) 자격증명이 덮이지 않게 service 경계에서 일괄 strip.
		// 실증: 첫 BOOTSTRAP 도달 run 이 stack select 에서 InvalidAccessKey 로 실패.
		Map<String, String> sanitized = CspCredentialPulumiConfigMapper.stripCspEnv(environmentOverrides);
		var selectResult = pulumiCommandService.selectStack(stackName, sanitized);
		if (!selectResult.isSuccess()) {
			throw new IllegalStateException("Failed to select stack: " + selectResult.getStderr());
		}
		return pulumiCommandService.stackOutputs(showSecrets, sanitized);
	}

	@Override
	public ProvisioningOutput typedStackOutputs(String stackName, Map<String, String> environmentOverrides) {
		Map<String, Object> raw = stackOutputs(stackName, true, environmentOverrides);
		return provisioningOutputMapper.map(raw);
	}

	@Override
	public void destroy(String stackName) {
		destroy(stackName, Map.of());
	}

	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void destroy(String stackName, Map<String, String> environmentOverrides) {
		assertEnabled();
		// Caller 가 raw CSP env 를 전달할 수 있어 mapper 로 strip — process env 가 state
		// backend 자격증명 (host AWS_*) 만 인식하게 보장. Stack config (aws:accessKey 등) 는
		// provision 시 set 되어 stack 안에 영속화되어 있음 (Pulumi default provider 자동 사용).
		Map<String, String> sanitized = CspCredentialPulumiConfigMapper.stripCspEnv(environmentOverrides);
		var selectResult = pulumiCommandService.selectOrCreateStack(stackName, sanitized);
		if (!selectResult.isSuccess()) {
			throw new IllegalStateException("Failed to select stack before destroy: " + selectResult.getStderr());
		}
		var destroyResult = staleLockGuard.run(stackName, sanitized,
				() -> pulumiCommandService.destroy(sanitized));
		if (!destroyResult.isSuccess()) {
			throw new IllegalStateException("Failed to destroy stack: " + destroyResult.getStderr());
		}
		var removeResult = pulumiCommandService.removeStack(stackName, sanitized);
		if (!removeResult.isSuccess()) {
			throw new IllegalStateException("Failed to remove stack: " + removeResult.getStderr());
		}
	}

	/** stderr 우선, 비어 있으면 stdout 의 diagnostics (error 항목) 추출 — 마지막 수단은 stdout tail. */
	private static String extractFailureDetail(String stderr, String stdout) {
		if (stderr != null && !stderr.isBlank()) {
			return stderr;
		}
		if (stdout == null || stdout.isBlank()) {
			return "<no output>";
		}
		try {
			var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(stdout);
			StringBuilder sb = new StringBuilder();
			for (var d : root.path("diagnostics")) {
				if ("error".equals(d.path("severity").asText())) {
					sb.append(d.path("message").asText()).append('\n');
				}
			}
			if (!sb.isEmpty()) {
				return sb.toString().strip();
			}
		} catch (Exception ignored) {
			// stdout 이 JSON 이 아니면 아래 tail fallback.
		}
		return stdout.substring(Math.max(0, stdout.length() - 500));
	}

	private void assertEnabled() {
		if (!config.isEnabled()) {
			throw new IllegalStateException("Pulumi provisioning is disabled");
		}
	}

	private boolean isSecretKey(String key) {
		String normalized = key.toLowerCase();
		return normalized.contains("password")
				|| normalized.contains("secret")
				|| normalized.contains("token")
				|| normalized.contains("privatekey");
	}

	private String valueOrDefault(String value, String defaultValue) {
		return value == null || value.isBlank() ? defaultValue : value;
	}
}
