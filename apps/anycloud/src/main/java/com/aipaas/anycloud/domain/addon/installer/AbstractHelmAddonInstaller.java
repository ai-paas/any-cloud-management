package com.aipaas.anycloud.domain.addon.installer;

import com.aipaas.anycloud.domain.addon.ClusterAddonEntity;
import com.aipaas.anycloud.domain.addon.internal.AddonRbacBindingHook;
import com.aipaas.anycloud.domain.kube.KindResolver;
import io.aipaas.cluster.agent.runtime.HelmReleaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Base implementation — 모든 helm-based addon installer 의 공통 install/uninstall 흐름.
 *
 * <p>구현체는 보통 {@link #type()} 만 override. 도메인-특화 후속 로직 (예: monitoring 의 GPU detect)
 * 이 필요하면 {@link #onAfterInstall} hook 활용.
 *
 * <p>Helm install path: {@link HelmReleaseService#install} — agent gRPC INSTALL_ADDON.
 * RepoURL 명시 alias resolve 의존 제거.
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractHelmAddonInstaller implements AddonInstaller {

    protected final HelmReleaseService helmReleaseService;

    /**
     * addon install 직후 자동 cache invalidate. Velero / Monitoring / Ingress
     * 어떤 addon 이든 CRD 를 새로 등록할 가능성이 있으므로 install 후 무조건 flush. {@code @Autowired}
     * field injection (non-final → Lombok ctor 미포함) → 6개 subclass 시그니처 보존. {@link ObjectProvider}
     * 로 optional — bean 미존재 (test) 환경 호환.
     */
    @Autowired(required = false)
    private ObjectProvider<KindResolver> kindResolverProvider;

    /**
     * Catalog rbac.groupBindings 자동 적용/cleanup hook. starter 의 BindingApplyClient 통해
     * cluster 에 ClusterRoleBinding apply, uninstall 시 label 매칭 일괄 삭제. starter 미설치
     * 환경에서는 ObjectProvider lazy 가 noop 보장.
     */
    @Autowired(required = false)
    private ObjectProvider<AddonRbacBindingHook> rbacBindingHookProvider;

    @Override
    public void install(ClusterAddonEntity addon) {
        // agent 의 INSTALL_ADDON handler 가 `repo/name` 형식 검증.
        // HelmReleaseService 의 chart param 은 그대로 forward — backend 가 결합해서 전달.
        String chartRef = addon.getChartRepo() + "/" + addon.getChartName();
        log.info(
                "AddonInstaller[{}]: install start cluster={} release={} chart={}:{}",
                type(),
                addon.getClusterId(),
                addon.getReleaseName(),
                chartRef,
                addon.getChartVersion());

        helmReleaseService.install(
                addon.getClusterId(),
                addon.getReleaseName(),
                chartRef, // "prometheus-community/kube-prometheus-stack"
                addon.getRepoUrl(), // 명시 URL 전달 (null OK)
                null, // chartTarballBase64 — pre-fetch 미사용
                addon.getChartVersion(),
                addon.getNamespace(),
                addon.getValuesYaml(),
                true); // createNamespace — addon namespace 자동 생성

        // addon install 직후 cache flush — 신규 CRD 가 추가되었을 가능성 cover. invalidate 는 idempotent
        // 이므로 CRD 미추가 케이스에서도 다음 호출 시 한 번만 miss → 재캐시 비용만 발생. side-effect 안전.
        KindResolver kindResolver = kindResolverProvider == null ? null : kindResolverProvider.getIfAvailable();
        if (kindResolver != null) {
            try {
                kindResolver.invalidate(addon.getClusterId());
            } catch (RuntimeException e) {
                log.debug(
                        "AddonInstaller[{}]: kind cache invalidate failed (non-fatal) cluster={}: {}",
                        type(),
                        addon.getClusterId(),
                        e.toString());
            }
        }

        onAfterInstall(addon);

        // catalog rbac.groupBindings 자동 apply (binding 생성). starter 미설치면 noop.
        invokeRbacHook(addon, true);

        log.info(
                "AddonInstaller[{}]: install done cluster={} release={}",
                type(),
                addon.getClusterId(),
                addon.getReleaseName());
    }

    @Override
    public void uninstall(ClusterAddonEntity addon) {
        log.info(
                "AddonInstaller[{}]: uninstall cluster={} release={}",
                type(),
                addon.getClusterId(),
                addon.getReleaseName());

        // uninstall 직전에 binding cleanup. helm release 제거가 ClusterRoleBinding 도 같이
        // 정리하지 않음 (chart 가 자체 RBAC 만들지 않은 경우). starter 가 만든 anycloud-labeled
        // binding 을 명시 cleanup.
        invokeRbacHook(addon, false);

        helmReleaseService.uninstall(
                addon.getClusterId(),
                addon.getReleaseName(),
                addon.getNamespace(),
                false, // keepHistory
                false); // wait
        onAfterUninstall(addon);
    }

    private void invokeRbacHook(ClusterAddonEntity addon, boolean install) {
        AddonRbacBindingHook hook = rbacBindingHookProvider == null ? null : rbacBindingHookProvider.getIfAvailable();
        if (hook == null) return;
        try {
            if (install) hook.onInstall(addon);
            else hook.onUninstall(addon);
        } catch (RuntimeException e) {
            log.warn(
                    "AddonRbacBindingHook {} failed cluster={} addon={}: {}",
                    install ? "install" : "uninstall",
                    addon.getClusterId(),
                    addon.getCatalogId(),
                    e.toString());
        }
    }

    /** post-install hook — domain-specific 후속 로직 (override 선택). */
    protected void onAfterInstall(ClusterAddonEntity addon) {
        // no-op
    }

    /** post-uninstall hook. */
    protected void onAfterUninstall(ClusterAddonEntity addon) {
        // no-op
    }
}
