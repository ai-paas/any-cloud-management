package io.aipaas.cluster.provisioning.core;

import lombok.Builder;
import lombok.Getter;

/**
 * Pulumi CLI subprocess 실행 결과.
 *
 * <p>caller 는 {@link #isSuccess()} 로 exit code 0 검사 후 {@link #getStdout()} 의 JSON 을 파싱.
 * stderr 는 진단/디버깅 용 — 운영 audit 로그에 기록.
 */
@Getter
@Builder
public class PulumiCommandResult {

	private final int exitCode;
	private final String stdout;
	private final String stderr;

	public boolean isSuccess() {
		return exitCode == 0;
	}
}
