package com.aipaas.anycloud.domain.kube.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * L3 — K8sKinds 의 isClusterScoped 거동 회귀 방지. 신규 cluster-scoped kind 추가 시
 * 본 테스트가 회귀를 잡아 ClusterKubernetesController 의 ns 무시 분기와 동기화한다.
 */
class K8sKindsTest extends AbstractUnitTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "nodes",
                "namespaces",
                "persistentvolumes",
                "storageclasses",
                "customresourcedefinitions",
                "NODES",
                "Nodes",
                "PersistentVolumes" // 대소문자 무관
            })
    void isClusterScoped_clusterScopedKinds_returnsTrue(String kind) {
        assertThat(K8sKinds.isClusterScoped(kind)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "pods",
                "services",
                "deployments",
                "configmaps",
                "secrets",
                "persistentvolumeclaims", // PV != PVC
                "jobs",
                "cronjobs",
                "clusterroles", // 정의 안 됨 — 명시적으로 false 회귀 방지
                ""
            })
    void isClusterScoped_otherKinds_returnsFalse(String kind) {
        assertThat(K8sKinds.isClusterScoped(kind)).isFalse();
    }

    @Test
    void isClusterScoped_null_returnsFalse() {
        assertThat(K8sKinds.isClusterScoped(null)).isFalse();
    }

    @Test
    void clusterScoped_setSize_matchesContract() {
        // 의도된 5개. 변경 시 ClusterKubernetesController doc + url-migration / v1-reference 동기 확인 필요.
        assertThat(K8sKinds.CLUSTER_SCOPED).hasSize(5);
    }
}
