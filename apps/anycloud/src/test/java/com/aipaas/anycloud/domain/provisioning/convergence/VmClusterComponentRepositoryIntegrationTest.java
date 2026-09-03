package com.aipaas.anycloud.domain.provisioning.convergence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Flyway DDL 과 JPA 매핑이 실제 MariaDB 에서 일치하는지 확인. cluster_addon 은 id 컬럼이
 * varchar(36) 으로 생성돼 42자 값 insert 가 항상 깨졌다 — 같은 회귀를 여기서 차단한다.
 */
@Transactional
class VmClusterComponentRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    VmClusterComponentRepository repository;

    @Autowired
    VmClusterRepository vmClusterRepository;

    /**
     * vm_cluster_component 는 vm_cluster 를 FK 로 참조한다. 실제 부모 row 없이는 insert 가
     * 막히므로 매 테스트가 자기 클러스터를 만든다.
     */
    private String newClusterId(String name) {
        VmClusterEntity e = new VmClusterEntity();
        e.setClusterName(name);
        e.setClusterProvider("AWS");
        e.setProvisioningStatus(VmClusterStatus.PROVISIONING);
        e.setStackName("anycloud-" + name);
        e.setRegion("ap-northeast-2");
        e.setEnvironment("test");
        e.setCreatedAt(LocalDateTime.now());
        return vmClusterRepository.saveAndFlush(e).getId();
    }

    private VmClusterComponentEntity newComponent(String vmClusterId, ComponentType type) {
        VmClusterComponentEntity entity = new VmClusterComponentEntity();
        entity.setVmClusterId(vmClusterId);
        entity.setComponentType(type);
        entity.setRequirement(Requirement.REQUIRED);
        entity.setHealth(ComponentHealth.UNKNOWN);
        return entity;
    }

    @Test
    void generatedIdFitsColumn() {
        // "vmcc-" + UUID = 41자. 컬럼이 64 미만이면 여기서 깨진다.
        VmClusterComponentEntity saved =
                repository.saveAndFlush(newComponent(newClusterId("conv-01"), ComponentType.GPU_OPERATOR));
        assertThat(saved.getId()).startsWith("vmcc-").hasSizeGreaterThan(36);
        assertThat(repository.findById(saved.getId())).isPresent();
    }

    @Test
    void defaultsAreAppliedOnPersist() {
        VmClusterComponentEntity saved =
                repository.saveAndFlush(newComponent(newClusterId("conv-02"), ComponentType.AGENT));
        assertThat(saved.getAttempts()).isZero();
        assertThat(saved.getAutoRepair()).isTrue();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void findByVmClusterId_returnsAllComponents() {
        String clusterId = newClusterId("conv-03");
        repository.save(newComponent(clusterId, ComponentType.GPU_DRIVER));
        repository.saveAndFlush(newComponent(clusterId, ComponentType.GPU_OPERATOR));
        assertThat(repository.findByVmClusterId(clusterId)).hasSize(2);
    }

    @Test
    void findByVmClusterIdAndComponentType_returnsSingleRow() {
        String clusterId = newClusterId("conv-04");
        repository.saveAndFlush(newComponent(clusterId, ComponentType.INGRESS));
        assertThat(repository.findByVmClusterIdAndComponentType(clusterId, ComponentType.INGRESS))
                .isPresent();
        assertThat(repository.findByVmClusterIdAndComponentType(clusterId, ComponentType.AGENT))
                .isEmpty();
    }

    @Test
    void lastErrorAcceptsLongText() {
        // CSP stderr 는 수 KB 다. varchar 로 잘리면 진단이 불가능해진다.
        VmClusterComponentEntity entity = newComponent(newClusterId("conv-05"), ComponentType.GPU_DRIVER);
        entity.setLastError("x".repeat(4000));
        entity.setLastProbedAt(ZonedDateTime.now());
        assertThat(repository.saveAndFlush(entity).getLastError()).hasSize(4000);
    }
}
