package com.aipaas.anycloud.domain.kube.model;

import java.util.Locale;
import java.util.Set;

/**
 * K8s kind 식별자 도메인 상수. 컨트롤러/서비스에서 kind 분류 (cluster-scoped vs namespaced)
 * 가 필요할 때 단일 진실 소스. 모든 kind 는 RESTful path 컨벤션을 따라 소문자 복수형.
 */
public final class K8sKinds {

    /**
     * Namespace 가 없는 kind 들. {@code /v1/clusters/{c}/namespaces/{ns}/{kind}} 에서 ns path 값을
     * 무시할지 판단할 때 사용. (kubectl 의 {@code -n} 무시 거동과 동일.)
     */
    public static final Set<String> CLUSTER_SCOPED =
            Set.of("nodes", "namespaces", "persistentvolumes", "storageclasses", "customresourcedefinitions");

    public static boolean isClusterScoped(String kind) {
        return kind != null && CLUSTER_SCOPED.contains(kind.toLowerCase(Locale.ROOT));
    }

    private K8sKinds() {}
}
