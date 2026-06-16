package com.aipaas.anycloud.domain.cluster.kubeconfig;

/**
 * kubeconfig 발급 대상 identity(=namespace + ServiceAccount) 를 결정하는 단일 seam.
 *
 * <p>kubeconfig 는 항상 agent 의 {@code GENERATE_KUBECONFIG} (TokenRequest) 로 발급되며, 토큰은
 * 반드시 "특정 namespace 의 특정 SA"에 묶인다. "누구로 발급할지"는 시간이 지나며 바뀌는 유일한 축이므로
 * 한 곳(본 resolver)에 모은다 — endpoint/installer 의 VM 분기·기본값 산재를 제거.
 *
 * <p>전략 진화 (endpoint 계약 변경 없이 additive):
 * <ul>
 *   <li>현재: 호출자가 SA 를 명시하면 그대로; 미지정 + VM(PULUMI) cluster 면 chart-생성 admin SA
 *       (cluster-admin) 로 기본 — 전체 권한 다운로드. 그 외엔 SERVICE_ACCOUNT_REQUIRED.</li>
 *   <li>impersonation/per-user RBAC 활성 시: 호출 사용자 신원으로 per-user/group SA 해석 전략 추가.
 *       admin SA 기본은 {@code agent.kubeconfig.admin-default-enabled=false} 로 break-glass 강등.</li>
 * </ul>
 */
public interface KubeconfigIdentityResolver {

    /**
     * @param clusterName             대상 cluster (ClusterEntity.id)
     * @param requestedServiceAccount 호출자가 명시한 SA (nullable/blank → resolver 기본 전략)
     * @param requestedNamespace      호출자가 명시한 namespace (nullable/blank → SA 의 기본 ns)
     * @return 실제 발급에 사용할 (namespace, serviceAccount)
     * @throws KubeconfigExportService.KubeconfigExportException
     *         {@code SERVICE_ACCOUNT_REQUIRED} — 기본 전략으로도 SA 를 정할 수 없을 때 (예: registered
     *         cluster + SA 미지정).
     */
    ResolvedIdentity resolve(String clusterName, String requestedServiceAccount, String requestedNamespace);

    record ResolvedIdentity(String namespace, String serviceAccount) {}
}
