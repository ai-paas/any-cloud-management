package com.aipaas.anycloud.domain.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.cluster.mapper.ClusterMapper;
import com.aipaas.anycloud.domain.cluster.model.Cluster;
import com.aipaas.anycloud.domain.cluster.model.ClusterStatus;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * ClusterMapper 단위 테스트 — MapStruct instance ({@link Mappers#getMapper}) 호출 round-trip 검증.
 */
class ClusterMapperTest {

    private final ClusterMapper mapper = Mappers.getMapper(ClusterMapper.class);

    @Test
    void toDomain_nullEntity_returnsNull() {
        assertThat(mapper.toDomain(null)).isNull();
    }

    @Test
    void toEntity_nullDomain_returnsNull() {
        assertThat(mapper.toEntity(null)).isNull();
    }

    @Test
    void toDomain_mapsAllFields() {
        ClusterEntity e = ClusterEntity.builder()
                .id("demo-aws-01")
                .description("AWS prod cluster")
                .status(ClusterStatus.ACTIVE)
                .version("1.29.0")
                .clusterType("VM")
                .clusterProvider("AWS")
                .provisioningType("PULUMI")
                .provisioningStatus("DONE")
                .hasGpuNodes(true)
                .stackName("anycloud-stack-1")
                .build();

        Cluster d = mapper.toDomain(e);

        assertThat(d.id()).isEqualTo("demo-aws-01");
        assertThat(d.description()).isEqualTo("AWS prod cluster");
        assertThat(d.status()).isEqualTo("ACTIVE");
        assertThat(d.version()).isEqualTo("1.29.0");
        assertThat(d.clusterProvider()).isEqualTo("AWS");
        assertThat(d.hasGpuNodes()).isTrue();
        assertThat(d.stackName()).isEqualTo("anycloud-stack-1");
    }

    @Test
    void toDomain_nullStatus_handledGracefully() {
        ClusterEntity e = ClusterEntity.builder().id("c-1").status(null).build();

        Cluster d = mapper.toDomain(e);

        assertThat(d.status()).isNull();
    }

    @Test
    void roundTrip_preservesPersistableFields() {
        Cluster original = new Cluster(
                "c-1", "desc", "ACTIVE", "1.29.0", "VM", "AWS", "PULUMI", "DONE", true, "stack-1", null, null);

        ClusterEntity entity = mapper.toEntity(original);
        Cluster restored = mapper.toDomain(entity);

        assertThat(restored.id()).isEqualTo("c-1");
        assertThat(restored.description()).isEqualTo("desc");
        assertThat(restored.status()).isEqualTo("ACTIVE");
        assertThat(restored.hasGpuNodes()).isTrue();
    }
}
