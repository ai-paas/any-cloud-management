package com.aipaas.anycloud.service.provisioning;

import com.aipaas.anycloud.common.util.CommandExecutionSupport;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Backend graceful shutdown 시 in-flight Pulumi 프로세스에 SIGTERM 전파.
 *
 * <p>Pulumi 는 SIGTERM 수신 시 현재 resource 작업의 checkpoint 를 저장하고 state lock 을 해제한
 * 뒤 종료한다. 이 hook 없이 backend 가 죽으면 (배포 재기동 포함) lock file 이 state backend 에
 * 잔존 — 해당 stack 의 모든 후속 작업이 "currently locked" 로 실패.
 *
 * <p>2중 방어: 그래도 잔존한 lock 은 {@code PulumiProvisioningServiceImpl.runWithStaleLockRecovery}
 * 가 다음 작업 시점에 {@code pulumi cancel} 로 자동 해제.
 *
 * <p>⚠ Docker 환경에선 compose 의 {@code stop_grace_period} 가 본 hook 의 대기 시간보다 길어야
 * 한다 (짧으면 docker 가 SIGKILL 로 컨테이너 전체를 죽여 hook 이 중단됨).
 */
@Slf4j
@Component
public class PulumiProcessShutdownHook {

    private final Duration grace;

    public PulumiProcessShutdownHook(@Value("${pulumi.shutdown-grace-seconds:30}") long graceSeconds) {
        this.grace = Duration.ofSeconds(graceSeconds);
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Shutting down — terminating in-flight Pulumi processes (grace {}s)", grace.toSeconds());
        CommandExecutionSupport.shutdownAll(grace);
    }
}
