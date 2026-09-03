package com.aipaas.anycloud.domain.addon.installer;

import com.aipaas.anycloud.domain.addon.ClusterAddonEntity;
import com.aipaas.anycloud.domain.addon.model.AddonType;

/**
 * Per-{@link AddonType} install/uninstall strategy.
 *
 * <p>Listener 가 message consume 시점에 {@link AddonInstallerRegistry} 로 type → bean lookup →
 * install/uninstall 호출. 각 구현체는 type-specific 로직 (예: MONITORING 은 dcgm-exporter 동반 install,
 * VELERO 는 BackupPolicy 후속 install) + 기존 starter installer 위임.
 *
 * <p>구현체는 RuntimeException 으로 실패 signal — listener 가 catch → addon row state FAILED.
 */
public interface AddonInstaller {

    AddonType type();

    /** Helm release 설치. 성공 시 release name 등 식별자 반환. 실패 시 exception. */
    void install(ClusterAddonEntity addon);

    /** Helm release 제거. 멱등 — 이미 삭제됐어도 정상 종료. */
    void uninstall(ClusterAddonEntity addon);
}
