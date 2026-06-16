package io.aipaas.cluster.provisioning.service;

import io.aipaas.cluster.provisioning.core.PulumiCommandResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Pulumi CLI 호출 추상화.
 *
 * <p>host backend 가 구현체 (PulumiCommandServiceImpl) 를 자체 제공하거나, starter 의 default impl 을
 * 사용. impl 자체는 host config 결합 큰 부분 (PulumiProperties, CommandExecutionSupport) 의 SPI 설계
 * 필요.
 */
public interface PulumiCommandService {

	PulumiCommandResult selectOrCreateStack(String stackName);

	PulumiCommandResult selectOrCreateStack(String stackName, Map<String, String> environmentOverrides);

	PulumiCommandResult selectStack(String stackName);

	PulumiCommandResult selectStack(String stackName, Map<String, String> environmentOverrides);

	PulumiCommandResult setConfig(String key, String value, boolean secret);

	PulumiCommandResult setConfig(String key, String value, boolean secret, Map<String, String> environmentOverrides);

	PulumiCommandResult up();

	PulumiCommandResult up(Map<String, String> environmentOverrides);

	/**
	 * Streaming variant. {@code pulumi up --json} 으로 실행, stdout 의 각 line (engine event JSON) 을
	 * 파싱해 {@link ProvisionEventBus} 로 push.
	 *
	 * @param operationId    SSE filtering 용 — event.operationId 로 동봉.
	 * @param environmentOverrides Pulumi 환경 변수 추가/덮어쓰기.
	 */
	PulumiCommandResult upWithEvents(String operationId, Map<String, String> environmentOverrides);

	PulumiCommandResult destroy();

	PulumiCommandResult destroy(Map<String, String> environmentOverrides);

	/** Streaming destroy. up 과 동일. */
	PulumiCommandResult destroyWithEvents(String operationId, Map<String, String> environmentOverrides);

	PulumiCommandResult removeStack(String stackName);

	PulumiCommandResult removeStack(String stackName, Map<String, String> environmentOverrides);

	/**
	 * {@code pulumi stack rm --force --yes} — state resource 가 남아있어도 강제 삭제.
	 * Orphan stack file (옛 cred 로 쓰여진 state, destroy 실패한 잔존) 정리에 사용. CSP 자원은
	 * 정리 안 되므로 caller 가 별도로 책임진다.
	 */
	PulumiCommandResult removeStackForce(String stackName, Map<String, String> environmentOverrides);

	/**
	 * {@code pulumi cancel --yes <stack>} — stack 의 in-flight update lock 해제.
	 * Backend crash / 재기동으로 Pulumi 프로세스가 중간에 죽으면 lock file 이 state backend 에
	 * 잔존해 모든 후속 작업이 "stack is currently locked" 로 실패 — 그 stale lock 정리용.
	 */
	PulumiCommandResult cancel(String stackName, Map<String, String> environmentOverrides);

	Map<String, Object> stackOutputs();

	Map<String, Object> stackOutputs(boolean showSecrets);

	Map<String, Object> stackOutputs(boolean showSecrets, Map<String, String> environmentOverrides);

	PulumiCommandResult run(List<String> args, Duration timeout);

	PulumiCommandResult run(List<String> args, Duration timeout, Map<String, String> environmentOverrides);

	Path projectDir();
}
