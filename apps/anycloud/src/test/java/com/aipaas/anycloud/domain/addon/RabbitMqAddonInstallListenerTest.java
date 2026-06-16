package com.aipaas.anycloud.domain.addon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.addon.installer.AddonInstaller;
import com.aipaas.anycloud.domain.addon.installer.AddonInstallerRegistry;
import com.aipaas.anycloud.domain.addon.internal.AddonWebhookPublisher;
import com.aipaas.anycloud.domain.addon.internal.RabbitMqAddonInstallListener;
import com.aipaas.anycloud.domain.addon.model.AddonState;
import com.aipaas.anycloud.domain.addon.model.AddonType;
import com.aipaas.anycloud.domain.addon.model.AddonWorkflowMessage;
import com.aipaas.anycloud.domain.operation.OperationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * — RabbitMqAddonInstallListener handling logic (Mockito).
 *
 * <p>state machine 전이 + Operation 동기 + idempotency + 실패 시 rethrow (retry interceptor trigger)
 * 검증. 실제 RabbitMQ broker / Spring context 없음 — 핵심 분기 직접 호출.
 *
 * <p>Testcontainers broker 통합은 별도 sprint 권장 (CI 환경에 따라 무거움).
 */
class RabbitMqAddonInstallListenerTest {

    @Mock
    ClusterAddonRepository addonRepository;

    @Mock
    AddonInstallerRegistry installerRegistry;

    @Mock
    AddonInstaller installer;

    @Mock
    OperationService operationService;

    private RabbitMqAddonInstallListener listener;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // webhook ObjectProvider empty mock (URL 미설정 환경 시뮬레이션).
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<AddonWebhookPublisher> emptyWebhook =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(emptyWebhook.getIfAvailable()).thenReturn(null);
        listener = new RabbitMqAddonInstallListener(addonRepository, installerRegistry, operationService, emptyWebhook);
    }

    @Test
    void onInstall_happyPath_transitionsToSucceeded() {
        ClusterAddonEntity addon = pendingAddon();
        List<AddonState> stateLog = new ArrayList<>();
        when(addonRepository.findById("addon-1")).thenReturn(Optional.of(addon));
        when(addonRepository.save(any(ClusterAddonEntity.class))).thenAnswer(inv -> {
            ClusterAddonEntity a = inv.getArgument(0);
            stateLog.add(a.getState()); // snapshot — mutable entity 의 mutation history 추적
            return a;
        });
        when(installerRegistry.find(AddonType.MONITORING)).thenReturn(installer);

        listener.onInstall(new AddonWorkflowMessage("c1", "addon-1", "op-1", "req-1"));

        // INSTALLING → SUCCEEDED 두 step.
        assertThat(stateLog).containsExactly(AddonState.INSTALLING, AddonState.SUCCEEDED);
        assertThat(addon.getAttempts()).isEqualTo(1);
        verify(installer).install(any(ClusterAddonEntity.class));
        verify(operationService).markRunning("op-1");
        verify(operationService).complete(eq("op-1"), anyString());
        verify(operationService, never()).fail(anyString(), anyString());
    }

    @Test
    void onInstall_installerThrows_transitionsToFailedAndRethrows() {
        ClusterAddonEntity addon = pendingAddon();
        List<AddonState> stateLog = new ArrayList<>();
        when(addonRepository.findById("addon-1")).thenReturn(Optional.of(addon));
        when(addonRepository.save(any())).thenAnswer(inv -> {
            stateLog.add(((ClusterAddonEntity) inv.getArgument(0)).getState());
            return inv.getArgument(0);
        });
        when(installerRegistry.find(AddonType.MONITORING)).thenReturn(installer);
        doThrow(new RuntimeException("helm install timeout")).when(installer).install(any());

        assertThatThrownBy(() -> listener.onInstall(new AddonWorkflowMessage("c1", "addon-1", "op-1", null)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("helm install timeout");

        // INSTALLING → FAILED.
        assertThat(stateLog).containsExactly(AddonState.INSTALLING, AddonState.FAILED);
        assertThat(addon.getLastError()).contains("helm install timeout");
        verify(operationService).fail(eq("op-1"), anyString());
    }

    @Test
    void onInstall_alreadySucceeded_skipsInstaller() {
        ClusterAddonEntity addon = pendingAddon();
        addon.setState(AddonState.SUCCEEDED);
        when(addonRepository.findById("addon-1")).thenReturn(Optional.of(addon));

        listener.onInstall(new AddonWorkflowMessage("c1", "addon-1", "op-1", null));

        verify(installer, never()).install(any());
        verify(operationService).complete(eq("op-1"), anyString());
    }

    @Test
    void onInstall_addonNotFound_failsOperation() {
        when(addonRepository.findById("missing")).thenReturn(Optional.empty());

        listener.onInstall(new AddonWorkflowMessage("c1", "missing", "op-1", null));

        verify(installer, never()).install(any());
        verify(operationService).fail(eq("op-1"), anyString());
    }

    @Test
    void onInstall_noInstallerForType_failsAddon() {
        ClusterAddonEntity addon = pendingAddon();
        List<AddonState> stateLog = new ArrayList<>();
        when(addonRepository.findById("addon-1")).thenReturn(Optional.of(addon));
        when(addonRepository.save(any())).thenAnswer(inv -> {
            stateLog.add(((ClusterAddonEntity) inv.getArgument(0)).getState());
            return inv.getArgument(0);
        });
        when(installerRegistry.find(AddonType.MONITORING)).thenReturn(null);

        listener.onInstall(new AddonWorkflowMessage("c1", "addon-1", "op-1", null));

        // INSTALLING → FAILED (installer 부재).
        assertThat(stateLog).containsExactly(AddonState.INSTALLING, AddonState.FAILED);
        verify(operationService).fail(eq("op-1"), anyString());
    }

    @Test
    void onUninstall_happyPath_transitionsToDeleted() {
        ClusterAddonEntity addon = pendingAddon();
        addon.setState(AddonState.SUCCEEDED);
        List<AddonState> stateLog = new ArrayList<>();
        when(addonRepository.findById("addon-1")).thenReturn(Optional.of(addon));
        when(addonRepository.save(any())).thenAnswer(inv -> {
            stateLog.add(((ClusterAddonEntity) inv.getArgument(0)).getState());
            return inv.getArgument(0);
        });
        when(installerRegistry.find(AddonType.MONITORING)).thenReturn(installer);

        listener.onUninstall(new AddonWorkflowMessage("c1", "addon-1", "op-2", null));

        assertThat(stateLog).containsExactly(AddonState.DELETING, AddonState.DELETED);
        verify(installer).uninstall(any());
        verify(operationService).complete(eq("op-2"), anyString());
    }

    private static ClusterAddonEntity pendingAddon() {
        return ClusterAddonEntity.builder()
                .id("addon-1")
                .clusterId("c1")
                .addonType(AddonType.MONITORING)
                .releaseName("kube-prometheus-stack")
                .namespace("monitoring")
                .chartRepo("prometheus-community")
                .chartName("kube-prometheus-stack")
                .chartVersion("65.0.0")
                .state(AddonState.PENDING)
                .attempts(0)
                .enabled(true)
                .build();
    }
}
