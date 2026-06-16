package com.aipaas.anycloud.domain.provisioning;

import com.aipaas.anycloud.common.logging.LoggingMdc;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * VmCluster state transition audit history recorder.
 *
 * <p>caller (workflow / step services / command service) 가 {@code VmClusterEntity.transitionTo}
 * 호출 후 본 recorder 의 {@link #record} 를 호출. operator/사용자는 endpoint
 * {@code GET /v1/clusters/{name}/state-history} 로 시간순 transition history 조회.
 *
 * <p>state machine graph 가 invalid 라고 판정한 transition 도 {@code valid=false} row 로 남김 —
 * observation mode (default) 에서 그대로 진행됐어도 회귀 분석용 trace 보존.
 *
 * <p>독립 transaction (REQUIRES_NEW) — caller 의 비즈니스 TX 가 rollback 되어도 audit row 는 보존.
 * Recorder 자체 실패는 best-effort (caller flow 깨뜨리지 않음).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VmClusterStateHistoryRecorder {

    private final VmClusterStateHistoryRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(VmClusterEntity entity, VmClusterStatus from, VmClusterStatus to, String reason) {
        try {
            boolean valid = from == null || from.canTransitionTo(to);
            VmClusterStateHistoryEntity row = VmClusterStateHistoryEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .clusterName(entity.getClusterName())
                    .vmClusterId(entity.getId())
                    .fromState(from)
                    .toState(to)
                    .reason(reason)
                    .principal(MDC.get("principal"))
                    .requestId(LoggingMdc.snapshot().get(LoggingMdc.REQUEST_ID))
                    .valid(valid)
                    .build();
            repository.save(row);
            if (!valid) {
                log.warn(
                        "VmCluster state transition recorded as INVALID: {} {} → {} (reason={})",
                        entity.getClusterName(),
                        from,
                        to,
                        reason);
            }
        } catch (Exception e) {
            // best-effort — audit insertion 실패가 비즈니스 flow 를 막지 않음.
            log.warn(
                    "VmCluster state history record failed (cluster={}, from={}, to={}): {}",
                    entity.getClusterName(),
                    from,
                    to,
                    e.toString());
        }
    }
}
