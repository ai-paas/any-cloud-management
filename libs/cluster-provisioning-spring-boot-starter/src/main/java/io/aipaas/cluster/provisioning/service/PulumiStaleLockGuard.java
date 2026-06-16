package io.aipaas.cluster.provisioning.service;

import io.aipaas.cluster.provisioning.core.PulumiCommandResult;
import io.aipaas.cluster.provisioning.service.PulumiCommandService;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pulumi stale lock 자동 복구 공용 guard.
 *
 * <p>Backend crash / 재기동이 Pulumi 프로세스를 중간에 죽이면 lock file 이 state backend 에 잔존
 * — 해당 stack 의 모든 후속 작업 (up, destroy, preview, refresh) 이 "currently locked" 로 영구
 * 실패. lock 에러 감지 시 {@code pulumi cancel} 로 lock 해제 후 1 회 재시도.
 *
 * <p>⚠ HA (multi backend instance) 환경에선 lock 이 다른 살아있는 instance 의 in-flight
 * 작업일 수 있다 — 현재 단일 instance + workflow state machine 이 같은 stack 의 동시 작업을
 * 차단하므로 자동 cancel 이 안전. multi-instance 도입 시 lock 소유자 식별 (lock file 의
 * hostname/pid) 후 stale 판정으로 강화 필요.
 */
@Slf4j
@RequiredArgsConstructor
public class PulumiStaleLockGuard {

	private final PulumiCommandService pulumiCommandService;

	/**
	 * {@code command} 실행 — lock 에러 시 cancel 후 1 회 재시도. cancel 실패 시 원래 lock 에러
	 * 결과 그대로 반환 (caller 가 메시지 보고 수동 개입).
	 */
	public PulumiCommandResult run(String stackName, Map<String, String> environmentOverrides,
			Supplier<PulumiCommandResult> command) {
		var result = command.get();
		if (!result.isSuccess() && isLockError(result.getStderr())) {
			log.warn("Stale Pulumi lock detected on stack {} — running `pulumi cancel` then retrying once", stackName);
			var cancelResult = pulumiCommandService.cancel(stackName, environmentOverrides);
			if (!cancelResult.isSuccess()) {
				log.error("pulumi cancel failed for stack {}: {}", stackName, cancelResult.getStderr());
				return result;
			}
			result = command.get();
		}
		return result;
	}

	static boolean isLockError(String stderr) {
		return stderr != null && stderr.contains("currently locked");
	}
}
