package com.aipaas.anycloud.domain.operation.internal;

import com.aipaas.anycloud.domain.operation.OperationEntity;
import com.aipaas.anycloud.domain.operation.OperationRepository;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.operation.model.OperationState;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 끝나지 않는 작업을 닫는다.
 *
 * <p>워커가 죽거나 워크플로가 멈추면 operation 이 RUNNING 인 채로 남는다. 실제로 8일 넘게 RUNNING
 * 인 항목이 쌓여 있었고, 화면에서는 아직 진행 중인 것처럼 보였다. 진행 중과 방치된 것을 구분할 수
 * 없으면 작업 이력 목록 자체를 믿을 수 없다.
 *
 * <p>여기서 닫는 것은 <b>기록</b>이지 인프라가 아니다. 실제 자원은 그대로이므로, 상태가 틀어졌다면
 * 조정 루프와 사용자의 재시도가 이어받는다.
 */
@Slf4j
@Component
public class StaleOperationReaper {

    private static final List<OperationState> ACTIVE = List.of(OperationState.PENDING, OperationState.RUNNING);

    private final OperationRepository operationRepository;
    private final OperationService operationService;
    private final Duration staleAfter;

    public StaleOperationReaper(
            OperationRepository operationRepository,
            OperationService operationService,
            @Value("${anycloud.operation.stale-after:PT2H}") Duration staleAfter) {
        this.operationRepository = operationRepository;
        this.operationService = operationService;
        this.staleAfter = staleAfter;
    }

    @Scheduled(
            fixedDelayString = "${anycloud.operation.reap-interval-ms:600000}",
            initialDelayString = "${anycloud.operation.reap-initial-delay-ms:120000}")
    @SchedulerLock(name = "staleOperationReaper", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void reap() {
        List<OperationEntity> stale =
                operationRepository.findStaleActive(ACTIVE, LocalDateTime.now().minus(staleAfter));
        for (OperationEntity operation : stale) {
            try {
                operationService.fail(operation.getId(), reasonFor(operation));
                log.warn(
                        "응답 없는 작업을 실패로 닫음 id={} type={} resource={} step={}",
                        operation.getId(),
                        operation.getType(),
                        operation.getResourceId(),
                        operation.getCurrentStep());
            } catch (Exception e) {
                // 하나가 실패해도 나머지는 닫아야 한다.
                log.warn("작업 정리 실패 id={}: {}", operation.getId(), e.toString());
            }
        }
    }

    /** "FAILED" 만 남으면 진짜 실패와 방치를 구분할 수 없다. */
    private String reasonFor(OperationEntity operation) {
        String step = operation.getCurrentStep() == null ? "알 수 없는 단계" : operation.getCurrentStep();
        return step + " 에서 " + staleAfter.toMinutes() + "분 넘게 응답이 없어 실패로 처리했습니다. " + "자원은 그대로이므로 상세에서 현재 상태를 확인해주세요.";
    }
}
