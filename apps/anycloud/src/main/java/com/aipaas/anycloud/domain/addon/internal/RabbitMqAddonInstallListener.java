package com.aipaas.anycloud.domain.addon.internal;

import com.aipaas.anycloud.domain.addon.ClusterAddonEntity;
import com.aipaas.anycloud.domain.addon.ClusterAddonRepository;
import com.aipaas.anycloud.domain.addon.installer.AddonInstaller;
import com.aipaas.anycloud.domain.addon.installer.AddonInstallerRegistry;
import com.aipaas.anycloud.domain.addon.model.AddonState;
import com.aipaas.anycloud.domain.addon.model.AddonWorkflowMessage;
import com.aipaas.anycloud.domain.operation.OperationService;
import java.time.ZonedDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Addon install/uninstall queue consumer.
 *
 * <p>Message → DB latest spec re-fetch → state machine 전이 (ENQUEUED→INSTALLING→SUCCEEDED|FAILED)
 * + Operation row 동기 (progress / complete / fail). 실패는 RabbitMQ retry interceptor 가
 * stateless backoff → maxAttempts 초과 시 DLQ.
 *
 * <p>Toggle: {@code addon-workflow.worker-enabled=false} 면 listener 비활성 (publish 만 가능).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "addon-workflow", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
// bootstrap-worker container 는 SPRING_MAIN_WEB_APPLICATION_TYPE=none 으로
// servlet/gRPC 비활성 — agent session 부재라 helm command 송신 불가. servlet mode (= backend container)
// 에서만 listener 활성. -fix 와 동일 패턴. addon-workflow.worker-enabled toggle 보다 환경
// (web vs non-web) 기반 자동 감지가 정합 — 운영자가 worker config 까지 신경 쓸 필요 없음.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RabbitMqAddonInstallListener {

    private final ClusterAddonRepository addonRepository;
    private final AddonInstallerRegistry installerRegistry;
    private final OperationService operationService;
    // optional webhook on state change. ObjectProvider 로 lazy 주입 — bean 부재 시 skip.
    private final org.springframework.beans.factory.ObjectProvider<AddonWebhookPublisher> webhookProvider;

    // @RabbitListener 의 placeholder 는 Spring property resolver — AddonWorkflowProperties
    // 의 Java default 와 별개. application.yaml 에 명시 안 되어 있어도 SpEL fallback (`: default`) 으로
    // resolve. fallback 값은 AddonWorkflowProperties default 와 동일 (drift 방지).
    @RabbitListener(queues = "${addon-workflow.install-queue:addon.install}")
    @Transactional
    public void onInstall(AddonWorkflowMessage message) {
        try (MDC.MDCCloseable c = withRequestId(message)) {
            processInstall(message);
        }
    }

    @RabbitListener(queues = "${addon-workflow.uninstall-queue:addon.uninstall}")
    @Transactional
    public void onUninstall(AddonWorkflowMessage message) {
        try (MDC.MDCCloseable c = withRequestId(message)) {
            processUninstall(message);
        }
    }

    private void processInstall(AddonWorkflowMessage message) {
        log.info(
                "AddonInstallListener: receive cluster={} addon={} op={}",
                message.clusterId(),
                message.addonId(),
                message.operationId());

        Optional<ClusterAddonEntity> opt = addonRepository.findById(message.addonId());
        if (opt.isEmpty()) {
            log.warn("AddonInstallListener: addon row not found id={} — drop", message.addonId());
            failOperation(message.operationId(), "addon row not found");
            return;
        }
        ClusterAddonEntity addon = opt.get();

        // idempotency — 동시 enqueue 또는 이미 SUCCEEDED 인 경우 skip.
        if (addon.getState() == AddonState.SUCCEEDED) {
            log.info(
                    "AddonInstallListener: already SUCCEEDED cluster={} addon={} — skip",
                    message.clusterId(),
                    message.addonId());
            completeOperation(message.operationId(), "already installed");
            return;
        }
        if (addon.getState() == AddonState.INSTALLING) {
            log.warn(
                    "AddonInstallListener: state is INSTALLING — concurrent execution? cluster={} addon={}",
                    message.clusterId(),
                    message.addonId());
        }

        addon.setState(AddonState.INSTALLING);
        addon.setAttempts(addon.getAttempts() == null ? 1 : addon.getAttempts() + 1);
        addon.setLastError(null);
        addon.setLastOperationId(message.operationId());
        addonRepository.save(addon);
        startOperation(message.operationId());

        AddonInstaller installer = installerRegistry.find(addon.getAddonType());
        if (installer == null) {
            fail(addon, "no installer for type=" + addon.getAddonType(), message.operationId());
            return;
        }

        try {
            installer.install(addon);
            addon.setState(AddonState.SUCCEEDED);
            addon.setUpdatedAt(ZonedDateTime.now());
            addonRepository.save(addon);
            completeOperation(
                    message.operationId(), "installed " + addon.getChartName() + ":" + addon.getChartVersion());
            notifyWebhook(addon, AddonState.SUCCEEDED);
            log.info(
                    "AddonInstallListener: SUCCEEDED cluster={} addon={} release={}",
                    addon.getClusterId(),
                    addon.getId(),
                    addon.getReleaseName());
        } catch (RuntimeException e) {
            fail(addon, e.toString(), message.operationId());
            // rethrow — retry interceptor 가 backoff 후 재시도, maxAttempts 초과 시 DLQ.
            throw e;
        }
    }

    private void processUninstall(AddonWorkflowMessage message) {
        log.info(
                "AddonInstallListener: uninstall cluster={} addon={} op={}",
                message.clusterId(),
                message.addonId(),
                message.operationId());

        Optional<ClusterAddonEntity> opt = addonRepository.findById(message.addonId());
        if (opt.isEmpty()) {
            log.warn("AddonInstallListener: uninstall target not found id={} — drop", message.addonId());
            failOperation(message.operationId(), "addon row not found");
            return;
        }
        ClusterAddonEntity addon = opt.get();
        addon.setState(AddonState.DELETING);
        addon.setLastOperationId(message.operationId());
        addonRepository.save(addon);
        startOperation(message.operationId());

        AddonInstaller installer = installerRegistry.find(addon.getAddonType());
        if (installer == null) {
            fail(addon, "no installer for type=" + addon.getAddonType(), message.operationId());
            return;
        }

        try {
            installer.uninstall(addon);
            addon.setState(AddonState.DELETED);
            addon.setUpdatedAt(ZonedDateTime.now());
            addonRepository.save(addon);
            completeOperation(message.operationId(), "uninstalled");
        } catch (RuntimeException e) {
            fail(addon, e.toString(), message.operationId());
            throw e;
        }
    }

    private void fail(ClusterAddonEntity addon, String errorMessage, String operationId) {
        addon.setState(AddonState.FAILED);
        addon.setLastError(errorMessage);
        addon.setUpdatedAt(ZonedDateTime.now());
        addonRepository.save(addon);
        failOperation(operationId, errorMessage);
        notifyWebhook(addon, AddonState.FAILED);
        log.error(
                "AddonInstallListener: FAILED cluster={} addon={} error={}",
                addon.getClusterId(),
                addon.getId(),
                errorMessage);
    }

    private void notifyWebhook(ClusterAddonEntity addon, AddonState newState) {
        AddonWebhookPublisher publisher = webhookProvider.getIfAvailable();
        if (publisher == null) return;
        try {
            publisher.notifyStateChange(addon, newState);
        } catch (Exception e) {
            log.warn("webhook notify failed (non-fatal): {}", e.toString());
        }
    }

    private void startOperation(String operationId) {
        if (operationId == null) return;
        try {
            operationService.markRunning(operationId);
        } catch (Exception e) {
            log.warn("startOperation failed id={}: {}", operationId, e.toString());
        }
    }

    private void completeOperation(String operationId, String resultMsg) {
        if (operationId == null) return;
        try {
            operationService.complete(operationId, resultMsg);
        } catch (Exception e) {
            log.warn("completeOperation failed id={}: {}", operationId, e.toString());
        }
    }

    private void failOperation(String operationId, String error) {
        if (operationId == null) return;
        try {
            operationService.fail(operationId, error);
        } catch (Exception e) {
            log.warn("failOperation failed id={}: {}", operationId, e.toString());
        }
    }

    private static MDC.MDCCloseable withRequestId(AddonWorkflowMessage message) {
        String rid = message.requestId();
        return rid == null ? MDC.putCloseable("requestId", "addon-worker") : MDC.putCloseable("requestId", rid);
    }
}
