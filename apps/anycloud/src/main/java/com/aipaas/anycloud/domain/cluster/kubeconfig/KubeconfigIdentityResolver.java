package com.aipaas.anycloud.domain.cluster.kubeconfig;

/**
 * kubeconfig 발급 대상, 즉 namespace 와 ServiceAccount 결정.
 *
 * <p>kubeconfig 는 agent 의 {@code GENERATE_KUBECONFIG} 가 TokenRequest 로 발급하므로 토큰은
 * 특정 namespace 의 특정 SA 에 종속. 발급 대상 결정은 정책 변경 축이므로 이 클래스로 집중.
 * endpoint 나 installer 에 기본값 분기 추가 금지.
 *
 * <p>현재는 호출자 SA 지정 시 해당 값 사용, 미지정 VM(PULUMI) cluster 는 chart 가 생성한
 * cluster-admin SA 적용. 즉 전체 권한 kubeconfig. 그 외에는 {@code SERVICE_ACCOUNT_REQUIRED} 거절.
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
