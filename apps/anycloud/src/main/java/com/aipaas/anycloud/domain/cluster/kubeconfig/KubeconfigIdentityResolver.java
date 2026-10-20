package com.aipaas.anycloud.domain.cluster.kubeconfig;

/**
 * kubeconfig 를 누구 앞으로 발급할지, 즉 namespace 와 ServiceAccount 를 정합니다.
 *
 * <p>kubeconfig 는 agent 의 {@code GENERATE_KUBECONFIG} 가 TokenRequest 로 발급하므로 토큰은 반드시
 * 특정 namespace 의 특정 SA 에 묶입니다. "누구로 발급할지" 는 정책이 바뀌는 축이라 여기 한 곳에
 * 모읍니다. endpoint 나 installer 에 기본값 분기를 두지 마세요.
 *
 * <p>지금은 호출자가 SA 를 지정하면 그대로 쓰고, 지정하지 않은 VM(PULUMI) cluster 는 chart 가 만든
 * cluster-admin SA 로 내려갑니다. 즉 전체 권한 kubeconfig 입니다. 그 밖에는
 * {@code SERVICE_ACCOUNT_REQUIRED} 로 거절합니다.
 */
public interface KubeconfigIdentityResolver {

    /**
     * @param clusterName             대상 cluster (ClusterEntity.id)
     * @param requestedServiceAccount 호출자가 명시한 SA (nullable/blank → resolver 기본 전략)
     * @param requestedNamespace      호출자가 명시한 namespace (nullable/blank → SA 의 기본 ns)
     * @return 실제 발급에 사용할 (namespace, serviceAccount)
     * @throws KubeconfigExportService.KubeconfigExportException
     *         {@code SERVICE_ACCOUNT_REQUIRED}: 기본 전략으로도 SA 를 정할 수 없을 때 (예: registered
     *         cluster 인데 SA 를 주지 않은 경우).
     */
    ResolvedIdentity resolve(String clusterName, String requestedServiceAccount, String requestedNamespace);

    record ResolvedIdentity(String namespace, String serviceAccount) {}
}
