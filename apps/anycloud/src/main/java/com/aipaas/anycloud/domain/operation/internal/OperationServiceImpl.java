package com.aipaas.anycloud.domain.operation.internal;

import com.aipaas.anycloud.common.logging.LoggingMdc;
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
    private final com.aipaas.anycloud.domain.operation.mapper.OperationMapper operationMapper;

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
        return repository.save(op);
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
            repository.save(op);
        }
        return op;
    }

    @Override
    public OperationEntity updateProgress(String operationId, String currentStep, Integer stepIndex, Integer percent) {
        OperationEntity op = mustFind(operationId);
        if (currentStep != null) op.setCurrentStep(currentStep);
        if (stepIndex != null) op.setStepIndex(stepIndex);
        if (percent != null) op.setPercent(Math.max(0, Math.min(100, percent)));
        return repository.save(op);
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
        return repository.save(op);
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
        return repository.save(op);
    }

    @Override
    public OperationEntity cancel(String operationId) {
        OperationEntity op = mustFind(operationId);
        op.setState(OperationState.CANCELLED);
        op.setEndedAt(LocalDateTime.now());
        return repository.save(op);
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
