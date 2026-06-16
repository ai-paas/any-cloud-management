package com.aipaas.anycloud.domain.cluster.kubeconfig.internal;

import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigExportService.KubeconfigExportException;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigIdentityResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 기본 identity 해석 전략.
 *
 * <ol>
 *   <li>호출자가 SA 명시 → 그대로 (namespace 미지정 시 "default").</li>
 *   <li>SA 미지정 + admin 기본 활성 + VM(PULUMI) cluster → chart-생성 admin SA
 *       (aipaas-admin / aipaas-system, cluster-admin) — 전체 권한 다운로드.</li>
 *   <li>그 외 → {@code SERVICE_ACCOUNT_REQUIRED} (registered cluster 는 존재하는 SA 명시 필요).</li>
 * </ol>
 *
 * <p>{@code agent.kubeconfig.admin-default-enabled=false} 로 2번 전략을 끄면 (impersonation/per-user
 * RBAC 도입 시 권장) admin SA 는 break-glass 로 강등되고, 모든 호출이 명시 SA(또는 추후 per-user 전략)를
 * 요구한다.
 */
@Component
@RequiredArgsConstructor
public class DefaultKubeconfigIdentityResolver implements KubeconfigIdentityResolver {

    private final ClusterRepository clusterRepository;

    @Value("${agent.kubeconfig.admin-service-account:aipaas-admin}")
    private String adminServiceAccount;

    @Value("${agent.kubeconfig.admin-namespace:aipaas-system}")
    private String adminNamespace;
    /**
     * VM cluster 의 admin SA 기본값 적용 여부. impersonation 활성 환경에서는 false 로 — admin SA 를
     * break-glass 로 강등하고 per-user 전략으로 대체.
     */
    @Value("${agent.kubeconfig.admin-default-enabled:true}")
    private boolean adminDefaultEnabled;

    @Override
    public ResolvedIdentity resolve(String clusterName, String requestedServiceAccount, String requestedNamespace) {
        if (requestedServiceAccount != null && !requestedServiceAccount.isBlank()) {
            String ns = (requestedNamespace == null || requestedNamespace.isBlank()) ? "default" : requestedNamespace;
            return new ResolvedIdentity(ns, requestedServiceAccount);
        }
        if (adminDefaultEnabled && isVmProvisioned(clusterName)) {
            String ns =
                    (requestedNamespace == null || requestedNamespace.isBlank()) ? adminNamespace : requestedNamespace;
            return new ResolvedIdentity(ns, adminServiceAccount);
        }
        throw new KubeconfigExportException(
                "SERVICE_ACCOUNT_REQUIRED",
                "serviceAccount 쿼리 파라미터가 필요합니다. cluster 에 존재하는 ServiceAccount + namespace 를 "
                        + "지정하세요 (VM 프로비저닝 cluster 는 미지정 시 admin SA 로 자동 발급).");
    }

    private boolean isVmProvisioned(String clusterName) {
        return clusterRepository
                .findById(clusterName)
                .map(com.aipaas.anycloud.domain.cluster.ClusterEntity::isVmProvisioned)
                .orElse(false);
    }
}
