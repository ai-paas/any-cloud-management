package com.aipaas.anycloud.domain.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * VmClusterRepository 의 JPA 매핑/쿼리가 실제 MariaDB 에서 동작하는지 확인. 회귀 시
 * column 타입 mismatch, naming convention, 인덱스 누락 등을 즉시 잡는다.
 */
@Transactional
class VmClusterRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    VmClusterRepository repository;

    @Test
    void saveAndFindById_roundtrip() {
        VmClusterEntity entity = newCluster("demo-aws-01");
        VmClusterEntity saved = repository.save(entity);

        assertThat(saved.getId()).isNotBlank();
        VmClusterEntity loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getClusterName()).isEqualTo("demo-aws-01");
        assertThat(loaded.getProvisioningStatus()).isEqualTo(VmClusterStatus.PROVISIONING);
    }

    @Test
    void findFirstByClusterNameOrderByCreatedAtDesc_returnsMostRecent() {
        String name = "demo-aws-multi";
        VmClusterEntity older = newCluster(name);
        older.setCreatedAt(LocalDateTime.now().minusDays(1));
        repository.save(older);

        VmClusterEntity newer = newCluster(name);
        newer.setCreatedAt(LocalDateTime.now());
        newer.setStackName("anycloud-newer");
        repository.save(newer);

        VmClusterEntity result =
                repository.findFirstByClusterNameOrderByCreatedAtDesc(name).orElseThrow();
        assertThat(result.getStackName()).isEqualTo("anycloud-newer");
    }

    @Test
    void findFirstByClusterName_returnsEmptyForUnknown() {
        assertThat(repository.findFirstByClusterNameOrderByCreatedAtDesc("never-existed"))
                .isEmpty();
    }

    private VmClusterEntity newCluster(String name) {
        VmClusterEntity e = new VmClusterEntity();
        e.setId(UUID.randomUUID().toString());
        e.setClusterName(name);
        e.setClusterProvider("AWS");
        e.setProvisioningStatus(VmClusterStatus.PROVISIONING);
        e.setStackName("anycloud-" + name);
        e.setRegion("ap-northeast-2");
        e.setEnvironment("test");
        e.setCreatedAt(LocalDateTime.now());
        return e;
    }
}
