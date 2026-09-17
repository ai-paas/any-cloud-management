package com.aipaas.anycloud.domain.operation.internal;

import com.aipaas.anycloud.common.logging.LoggingMdc;
import com.aipaas.anycloud.domain.events.ResourceChangedEvent;
import com.aipaas.anycloud.domain.operation.Operation;
import com.aipaas.anycloud.domain.operation.OperationEntity;
import com.aipaas.anycloud.domain.operation.OperationRepository;
import com.aipaas.anycloud.domain.operation.OperationService;
import com.aipaas.anycloud.domain.operation.model.OperationState;
import com.aipaas.anycloud.domain.operation.model.OperationType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class OperationServiceImpl implements OperationService {

    private final OperationRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final com.aipaas.anycloud.domain.operation.mapper.OperationMapper operationMapper;

    /** 한 자원에 동시 작업은 드물다. 그보다 깊이 쌓였다면 오래된 것은 시간 기반 정리가 걷어간다. */
    private static final int SUPERSEDE_SCAN_SIZE = 5;

    // lifecycle 메서드들 (start/markRunning/complete/fail/cancel) 은 REQUIRES_NEW 로
    // 격리 — caller (RabbitMqAddonInstallListener 등) 의 outer @Transactional 안에서 호출 시,
    // operation row not-found / RuntimeException 이 outer transaction 의 rollback-only 마크를
    // trigger 하지 않도록. operation 의 lifecycle 변경은 본질적으로 audit-style — 독립 atomic.
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OperationEntity start(
            OperationType type, String resourceType, String resourceId, String requestPayload, int totalSteps) {
        OperationEntity op = OperationEntity.builder()
                .id(generateId())
                .type(type)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .state(OperationState.PENDING)
                .totalSteps(totalSteps > 0 ? totalSteps : null)
                .percent(0)
                .requestPayload(requestPayload)
                .requestId(LoggingMdc.snapshot().get(LoggingMdc.REQUEST_ID))
                .principal(MDC.get("principal"))
                .build();
        log.info("Operation start: id={}, type={}, resource={}/{}", op.getId(), type, resourceType, resourceId);
        supersedeActive(type, resourceType, resourceId);
        return saveAndAnnounce(op);
    }

    /**
     * 같은 자원에 새 작업이 뜨면 앞선 작업은 다시 진행될 수 없다. 남겨두면 자원은 READY 인데
     * 화면은 중간 단계에 멈춰 있다. 시간 기반 정리로는 그 사이를 메우지 못한다.
     *
     * <p>정리에 실패해도 새 작업은 시작한다 — 기록 때문에 자원 생성을 막을 이유가 없다.
     */
    private void supersedeActive(OperationType type, String resourceType, String resourceId) {
        try {
            List<OperationEntity> recent = repository.findByResourceTypeAndResourceIdOrderByCreatedAtDesc(
                    resourceType, resourceId, PageRequest.of(0, SUPERSEDE_SCAN_SIZE));
            for (OperationEntity previous : recent) {
                if (previous.getState() != null && previous.getState().isTerminal()) {
                    continue;
                }
                previous.setErrorMessage("%s 작업이 시작되어 더 진행되지 않음".formatted(type));
                cancel(previous.getId());
            }
        } catch (Exception e) {
            log.warn("이전 작업을 닫지 못함 resource={}/{}: {}", resourceType, resourceId, e.toString());
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OperationEntity markRunning(String operationId) {
        OperationEntity op = mustFind(operationId);
        // 멱등: 이미 RUNNING/SUCCEEDED/FAILED/CANCELLED 면 no-op. startedAt 덮어쓰기 방지.
        // ClusterFacade 의 명시 호출과 workflow handler 의 updateActiveOperationProgress
        // (PENDING→RUNNING 자동 전환) 가 race 해도 안전.
        if (op.getState() == OperationState.PENDING) {
            op.setState(OperationState.RUNNING);
            op.setStartedAt(LocalDateTime.now());
            saveAndAnnounce(op);
        }
        return op;
    }

    @Override
    public OperationEntity updateProgress(String operationId, String currentStep, Integer stepIndex, Integer percent) {
        OperationEntity op = mustFind(operationId);
        if (currentStep != null) op.setCurrentStep(currentStep);
        if (stepIndex != null) op.setStepIndex(stepIndex);
        if (percent != null) op.setPercent(Math.max(0, Math.min(100, percent)));
        return saveAndAnnounce(op);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OperationEntity complete(String operationId, String resultPayload) {
        OperationEntity op = mustFind(operationId);
        op.setState(OperationState.SUCCEEDED);
        op.setPercent(100);
        op.setResultPayload(resultPayload);
        op.setEndedAt(LocalDateTime.now());
        log.info(
                "Operation completed: id={}, type={}, resource={}/{}",
                op.getId(),
                op.getType(),
                op.getResourceType(),
                op.getResourceId());
        return saveAndAnnounce(op);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OperationEntity fail(String operationId, String errorMessage) {
        OperationEntity op = mustFind(operationId);
        op.setState(OperationState.FAILED);
        op.setErrorMessage(errorMessage);
        op.setEndedAt(LocalDateTime.now());
        log.warn(
                "Operation failed: id={}, type={}, resource={}/{}, err={}",
                op.getId(),
                op.getType(),
                op.getResourceType(),
                op.getResourceId(),
                errorMessage);
        return saveAndAnnounce(op);
    }

    @Override
    public OperationEntity cancel(String operationId) {
        OperationEntity op = mustFind(operationId);
        op.setState(OperationState.CANCELLED);
        op.setEndedAt(LocalDateTime.now());
        return saveAndAnnounce(op);
    }

    /**
     * 저장과 알림을 한 통로로 묶는다. 저장 지점이 여섯 곳이라 따로 두면 언젠가 하나를 빠뜨리고,
     * 그 화면만 옛 상태로 남는다.
     */
    private OperationEntity saveAndAnnounce(OperationEntity op) {
        OperationEntity saved = repository.save(op);
        eventPublisher.publishEvent(new ResourceChangedEvent("operation", saved.getResourceId()));
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OperationEntity> findById(String operationId) {
        return repository.findById(operationId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Operation> findDomainById(String operationId) {
        return repository.findById(operationId).map(operationMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OperationEntity> listByResource(String resourceType, String resourceId, int limit) {
        return repository.findByResourceTypeAndResourceIdOrderByCreatedAtDesc(
                resourceType, resourceId, PageRequest.of(0, Math.max(1, Math.min(500, limit))));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OperationEntity> findLatestActiveByResource(String resourceType, String resourceId) {
        // 가장 최근 5개 row 중 non-terminal 첫 번째.
        // 동시 op 가 흔치 않지만, 있어도 최신 active 1개로 향하도록 best-effort.
        return repository
                .findByResourceTypeAndResourceIdOrderByCreatedAtDesc(resourceType, resourceId, PageRequest.of(0, 5))
                .stream()
                .filter(op -> op.getState() == null || !op.getState().isTerminal())
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OperationEntity> search(
            OperationState state, OperationType type, String resourceType, String resourceId, int limit) {
        return repository.search(
                state, type, resourceType, resourceId, PageRequest.of(0, Math.max(1, Math.min(500, limit))));
    }

    private OperationEntity mustFind(String id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Operation not found: " + id));
    }

    private static String generateId() {
        return "op-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
