package com.aipaas.anycloud.domain.provisioning.internal;

import com.aipaas.anycloud.common.util.CommandExecutionSupport;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Backend graceful shutdown 시 in-flight CLI 프로세스 (Helm / SSH) 에 SIGTERM 전파.
 *
 * <p>각 CLI 는 SIGTERM 수신 시 자체 cleanup 후 종료. hook 없이 backend 가 죽으면 자식 프로세스가
 * orphan 상태로 잔존.
 *
 * <p>⚠ Docker 환경에선 compose 의 {@code stop_grace_period} 가 본 hook 의 대기 시간보다 길어야
 * 한다 (짧으면 docker 가 SIGKILL 로 컨테이너 전체를 죽여 hook 이 중단됨).
 */
@Slf4j
@Component
public class CommandExecShutdownHook {

    private final Duration grace;

    public CommandExecShutdownHook(@Value("${command-exec.shutdown-grace-seconds:30}") long graceSeconds) {
        this.grace = Duration.ofSeconds(graceSeconds);
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Shutting down — terminating in-flight CLI processes (grace {}s)", grace.toSeconds());
        CommandExecutionSupport.shutdownAll(grace);
    }
}
