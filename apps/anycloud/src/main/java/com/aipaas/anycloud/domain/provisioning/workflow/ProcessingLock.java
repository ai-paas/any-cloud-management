package com.aipaas.anycloud.domain.provisioning.workflow;

import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 클러스터당 워크플로 메시지 동시 처리 1건 제한.
 *
 * <p>{@link WorkflowMessageGuard} 의 중복 차단은 처리 <b>완료 후</b> 기록되는
 * {@code last_processed_workflow_message_id} 기준. 재전달은 처리 <b>도중</b> 도착하므로 통과.
 * AMQP delivery ack 타임아웃으로 메시지가 재전달되면 동일 노드에 부트스트랩이 중복 실행.
 *
 * <p>소유권은 조건부 UPDATE 단일 쿼리로 획득. 읽고 쓰는 방식은 두 워커가 같은 틈에 진입 가능.
 */
@Slf4j
@Component
public class ProcessingLock {

    /**
     * 이 값보다 오래된 소유권은 워커 종료로 간주해 회수.
     *
     * <p>AMQP delivery ack 타임아웃(기본 30분)과 BOOTSTRAP 최악 예산(170분)보다 커야 함. 작으면
     * 실행 중인 워커의 락을 재전달이 탈취해 중복 실행 발생.
     */
    public static final Duration DEFAULT_STALE_AFTER = Duration.ofMinutes(180);

    private final VmClusterRepository vmClusterRepository;
    private final Duration staleAfter;

    public ProcessingLock(
            VmClusterRepository vmClusterRepository,
            @Value("${anycloud.vm-cluster.workflow.processing-stale-after:PT180M}") Duration staleAfter) {
        this.vmClusterRepository = vmClusterRepository;
        this.staleAfter = staleAfter;
    }

    /** 잡았으면 true. 다른 메시지가 처리 중이면 false. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean acquire(String vmClusterId, String messageId) {
        // 잠글 대상이 없는 메시지(일부 destroy 흐름)는 호출부 책임.
        if (vmClusterId == null || vmClusterId.isBlank() || messageId == null || messageId.isBlank()) {
            return true;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = vmClusterRepository.acquireProcessing(vmClusterId, messageId, now, now.minus(staleAfter));
        if (updated == 0) {
            log.warn("다른 메시지가 처리 중이라 건너뛴다 (vmClusterId={}, messageId={})", vmClusterId, messageId);
        }
        return updated > 0;
    }

    /**
     * 소유자와 무관하게 소유권 탈취. 삭제 전용.
     *
     * <p>삭제는 사용자의 복구 수단. 앞 단계 뒤에 대기시키면 정지된 프로비저닝 삭제에 수 시간
     * 소요. 앞 단계 결과는 폐기 대상.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean preempt(String vmClusterId, String messageId) {
        if (vmClusterId == null || vmClusterId.isBlank() || messageId == null || messageId.isBlank()) {
            return true;
        }
        vmClusterRepository.takeOverProcessing(vmClusterId, messageId, LocalDateTime.now());
        return true;
    }

    /** 자신이 획득한 소유권만 해제. 회수된 이후면 변경 없음. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String vmClusterId, String messageId) {
        if (vmClusterId == null || vmClusterId.isBlank() || messageId == null || messageId.isBlank()) {
            return;
        }
        vmClusterRepository.releaseProcessing(vmClusterId, messageId);
    }
}
