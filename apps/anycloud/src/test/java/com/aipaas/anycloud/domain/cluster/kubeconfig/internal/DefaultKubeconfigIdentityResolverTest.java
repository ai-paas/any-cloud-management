package com.aipaas.anycloud.domain.cluster.kubeconfig.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.domain.cluster.ClusterEntity;
import com.aipaas.anycloud.domain.cluster.ClusterRepository;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigExportService.KubeconfigExportException;
import com.aipaas.anycloud.domain.cluster.kubeconfig.KubeconfigIdentityResolver.ResolvedIdentity;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

class DefaultKubeconfigIdentityResolverTest extends AbstractUnitTest {

    @Mock
    ClusterRepository clusterRepository;

    private DefaultKubeconfigIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DefaultKubeconfigIdentityResolver(clusterRepository);
        ReflectionTestUtils.setField(resolver, "adminServiceAccount", "aipaas-admin");
        ReflectionTestUtils.setField(resolver, "adminNamespace", "aipaas-system");
        ReflectionTestUtils.setField(resolver, "adminDefaultEnabled", true);
    }

    @Test
    void explicitServiceAccount_usedAsIs_clusterNotConsulted() {
        ResolvedIdentity id = resolver.resolve("c1", "my-sa", "my-ns");
        assertThat(id.serviceAccount()).isEqualTo("my-sa");
        assertThat(id.namespace()).isEqualTo("my-ns");
        // 명시 SA 면 cluster 조회 불필요 — repository 미호출.
        org.mockito.Mockito.verifyNoInteractions(clusterRepository);
    }

    @Test
    void explicitSaWithoutNamespace_defaultsToDefaultNs() {
        ResolvedIdentity id = resolver.resolve("c1", "my-sa", null);
        assertThat(id.namespace()).isEqualTo("default");
        assertThat(id.serviceAccount()).isEqualTo("my-sa");
    }

    @Test
    void vmCluster_noServiceAccount_defaultsToAdminSa() {
        when(clusterRepository.findById("c1"))
                .thenReturn(Optional.of(ClusterEntity.builder()
                        .id("c1")
                        .provisioningType("PULUMI")
                        .build()));

        ResolvedIdentity id = resolver.resolve("c1", null, null);
        assertThat(id.serviceAccount()).isEqualTo("aipaas-admin");
        assertThat(id.namespace()).isEqualTo("aipaas-system");
    }

    @Test
    void registeredCluster_noServiceAccount_throwsServiceAccountRequired() {
        when(clusterRepository.findById("c1"))
                .thenReturn(Optional.of(ClusterEntity.builder()
                        .id("c1")
                        .provisioningType("IMPORTED")
                        .build()));

        assertThatThrownBy(() -> resolver.resolve("c1", null, null))
                .isInstanceOf(KubeconfigExportException.class)
                .satisfies(e ->
                        assertThat(((KubeconfigExportException) e).errorCode()).isEqualTo("SERVICE_ACCOUNT_REQUIRED"));
    }

    @Test
    void adminDefaultDisabled_vmClusterStillRequiresExplicitSa() {
        // P4 gate — impersonation 활성 환경 시뮬레이션: admin 기본값 off → VM 도 명시 SA 요구.
        ReflectionTestUtils.setField(resolver, "adminDefaultEnabled", false);
        // admin 기본 비활성이면 cluster 타입 확인 전에 막히므로 repository stub 불필요.
        assertThatThrownBy(() -> resolver.resolve("c1", null, null))
                .isInstanceOf(KubeconfigExportException.class)
                .satisfies(e ->
                        assertThat(((KubeconfigExportException) e).errorCode()).isEqualTo("SERVICE_ACCOUNT_REQUIRED"));
    }
}
