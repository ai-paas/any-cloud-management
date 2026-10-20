package com.aipaas.anycloud.domain.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import com.aipaas.anycloud.testsupport.AbstractIntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * VmClusterRepository 의 JPA 매핑과 쿼리가 실제 MariaDB 에서 동작하는지 검증. column 타입
 * 불일치, naming convention, 인덱스 누락 등의 회귀를 즉시 검출.
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

    @Test
    void degradedStatusPersistsToEnumColumn() {
        // provisioning_status 는 varchar 가 아닌 MySQL enum. V8 로 값을 추가하지 않으면
        // Java 만 수정한 상태에서 컴파일과 단위 테스트는 통과하고 이 지점에서 실패.
        VmClusterEntity entity = newCluster("degraded-round-trip");
        entity.setProvisioningStatus(VmClusterStatus.DEGRADED);
        VmClusterEntity saved = repository.saveAndFlush(entity);

        assertThat(repository.findById(saved.getId()).orElseThrow().getProvisioningStatus())
                .isEqualTo(VmClusterStatus.DEGRADED);
    }

    private VmClusterEntity newCluster(String name) {
        // id 미지정. @GeneratedValue(UUID) 가 설정.
        // 사전 지정 시 Hibernate 6.6 이 detached 로 판정해 INSERT 대신 UPDATE 를 전송하고,
        // 대상 row 부재로 ObjectOptimisticLockingFailureException 발생.
        // 운영 코드도 VmClusterEntity.builder() 사용 시 id 미지정.
        VmClusterEntity e = new VmClusterEntity();
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
