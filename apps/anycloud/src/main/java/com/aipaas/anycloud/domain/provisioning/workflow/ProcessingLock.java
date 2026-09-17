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
 * 한 클러스터를 한 번에 하나의 워크플로 메시지만 처리하게 한다.
 *
 * <p>{@link WorkflowMessageGuard} 의 중복 차단은 처리가 <b>끝난 뒤</b> 기록되는
 * {@code last_processed_workflow_message_id} 를 본다. 재전달은 처리 <b>도중</b> 오므로 그대로
 * 통과한다. AMQP delivery ack 타임아웃에 걸려 메시지가 재전달되면서 같은 노드에 부트스트랩이 두 번
 * 붙는 것을 실제로 관측했다.
 *
 * <p>소유권은 조건부 UPDATE 하나로 잡는다. 읽고 나서 쓰면 두 워커가 같은 틈으로 들어온다.
 */
@Slf4j
@Component
public class ProcessingLock {

    /**
     * 이보다 오래된 소유권은 워커가 죽은 것으로 보고 회수한다.
     *
     * <p>AMQP delivery ack 타임아웃(기본 30분)보다 길어야 한다. 짧으면 원래 워커가 아직 일하는
     * 중에 재전달이 락을 빼앗아 막으려던 중복 실행이 그대로 일어난다.
     */
    /**
     * BOOTSTRAP 최악 예산(170분)보다 길어야 한다. 짧으면 아직 도는 작업의 락을 남이 뺏어가
     * 같은 노드에 부트스트랩이 두 번 붙는다. 45분이던 시절 실제로 그랬다.
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
        // 잠글 대상이 없는 메시지(일부 destroy 흐름)는 호출부 책임으로 넘긴다.
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
     * 누가 쥐고 있든 가져온다. 삭제 전용이다.
     *
     * <p>삭제는 사용자의 탈출구다. 앞 단계 뒤에 줄을 세우면 멈춘 프로비저닝을 몇 시간 동안
     * 지울 수 없다. 앞 단계의 결과는 어차피 버려진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean preempt(String vmClusterId, String messageId) {
        if (vmClusterId == null || vmClusterId.isBlank() || messageId == null || messageId.isBlank()) {
            return true;
        }
        vmClusterRepository.takeOverProcessing(vmClusterId, messageId, LocalDateTime.now());
        return true;
    }

    /** 자기가 잡은 것만 놓는다. 이미 회수됐으면 아무것도 바꾸지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String vmClusterId, String messageId) {
        if (vmClusterId == null || vmClusterId.isBlank() || messageId == null || messageId.isBlank()) {
            return;
        }
        vmClusterRepository.releaseProcessing(vmClusterId, messageId);
    }
}
