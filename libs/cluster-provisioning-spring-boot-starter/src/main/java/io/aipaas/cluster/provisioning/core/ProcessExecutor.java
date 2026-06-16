package io.aipaas.cluster.provisioning.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OS 프로세스 spawn 추상화 (SPI 포트).
 *
 * <p>starter 의 {@code PulumiCommandService} 구현은 Pulumi CLI 오케스트레이션 <em>로직</em>(명령 구성,
 * 민감 인자 마스킹, {@code --json} 이벤트 파싱)만 소유하고, 실제 OS 프로세스 생성은 이 포트로 위임한다.
 * 그래서 host 는 자신의 프로세스 실행기 — 예: in-flight 추적 + graceful shutdown(SIGTERM) 을 갖춘 —
 * 를 주입할 수 있다. host 가 bean 을 등록하지 않으면 starter 의 {@code DefaultProcessExecutor} 가 쓰인다.
 *
 * <p>mechanism(프로세스 생성) / policy(Pulumi 명령) 분리: starter 는 정책, host 는 메커니즘.
 */
public interface ProcessExecutor {

	/**
	 * 명령을 실행하고 종료까지 대기한다. {@code timeout} 초과 시 강제 종료 후 예외.
	 *
	 * @param command          실행할 명령 + 인자 (첫 원소가 바이너리).
	 * @param workingDirectory 작업 디렉토리 (null 이면 현재 디렉토리).
	 * @param environment      추가/덮어쓸 환경 변수 (시스템 환경에 병합).
	 * @param timeout          최대 실행 시간.
	 */
	ExecResult execute(List<String> command, Path workingDirectory,
			Map<String, String> environment, Duration timeout);

	/**
	 * {@link #execute} 의 streaming 변형. stdout 을 line 단위로 {@code lineConsumer} 에 push 하면서
	 * 동시에 전체 dump 도 결과로 반환한다. consumer 가 던지는 예외는 swallow — 한 줄 처리 실패가 전체
	 * 명령을 중단시키지 않는다. {@code pulumi up --json} 의 engine event 스트리밍에 사용.
	 */
	ExecResult executeStreaming(List<String> command, Path workingDirectory,
			Map<String, String> environment, Duration timeout, Consumer<String> lineConsumer);

	/** 프로세스 실행 결과. exitCode 0 = 성공. */
	record ExecResult(int exitCode, String stdout, String stderr) {

		public boolean isSuccess() {
			return exitCode == 0;
		}
	}
}
