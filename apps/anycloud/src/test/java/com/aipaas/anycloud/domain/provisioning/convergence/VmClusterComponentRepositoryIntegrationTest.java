package com.aipaas.anycloud.domain.provisioning.convergence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipaas.anycloud.domain.provisioning.VmClusterEntity;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA 매핑이 실제 MariaDB 에서 동작하는지 확인.
 *
 * <p>이 테스트는 {@code ddl-auto=create-drop} 으로 만든 스키마를 쓴다 (test 프로파일이 Flyway 를
 * 끈다). 따라서 Flyway DDL 과의 일치는 검증하지 못한다 — 엔티티 애너테이션에 길이와 제약을 빠짐없이
 * 적어야 두 경로가 같은 스키마를 만든다. cluster_addon 은 id 가 varchar(36) 으로 생성돼 42자 값
 * insert 가 항상 깨졌다.
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
                repository.saveAndFlush(newComponent(newClusterId("conv-01"), ComponentType.AGENT));
        assertThat(saved.getId()).startsWith("vmcc-").hasSizeGreaterThan(36);
        assertThat(repository.findById(saved.getId())).isPresent();
    }

    @Test
    void defaultsAreAppliedOnPersist() {
        VmClusterComponentEntity saved =
                repository.saveAndFlush(newComponent(newClusterId("conv-02"), ComponentType.AGENT));
        assertThat(saved.getAttempts()).isZero();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void findByVmClusterId_scopesToOneCluster() {
        String a = newClusterId("conv-03a");
        String b = newClusterId("conv-03b");
        repository.save(newComponent(a, ComponentType.AGENT));
        repository.saveAndFlush(newComponent(b, ComponentType.AGENT));

        assertThat(repository.findByVmClusterId(a)).hasSize(1);
        assertThat(repository.findByVmClusterId(b)).hasSize(1);
    }

    @Test
    void uniqueConstraintPreventsDuplicatePerClusterAndType() {
        // 관측이 매 주기 돌아도 행은 하나여야 한다. 중복되면 상태가 갈린다.
        String clusterId = newClusterId("conv-03c");
        repository.saveAndFlush(newComponent(clusterId, ComponentType.AGENT));

        assertThatThrownBy(() -> repository.saveAndFlush(newComponent(clusterId, ComponentType.AGENT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByVmClusterIdAndComponentType_emptyForUnknownCluster() {
        String clusterId = newClusterId("conv-04");
        repository.saveAndFlush(newComponent(clusterId, ComponentType.AGENT));

        assertThat(repository.findByVmClusterIdAndComponentType(clusterId, ComponentType.AGENT))
                .isPresent();
        assertThat(repository.findByVmClusterIdAndComponentType("vmc-does-not-exist", ComponentType.AGENT))
                .isEmpty();
    }

    @Test
    void lastErrorAcceptsLongText() {
        // CSP stderr 는 수 KB 다. varchar 로 잘리면 진단이 불가능해진다.
        VmClusterComponentEntity entity = newComponent(newClusterId("conv-05"), ComponentType.AGENT);
        entity.setLastError("x".repeat(4000));
        entity.setLastProbedAt(ZonedDateTime.now());
        assertThat(repository.saveAndFlush(entity).getLastError()).hasSize(4000);
    }
}
