package com.aipaas.anycloud.domain.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.mapper.VmClusterStateHistoryMapper;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStateHistory;
import com.aipaas.anycloud.domain.provisioning.model.VmClusterStatus;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * VmClusterStateHistoryMapper 단위 테스트 — MapStruct instance ({@link Mappers#getMapper})
 * round-trip 검증.
 */
class VmClusterStateHistoryMapperTest {

    private final VmClusterStateHistoryMapper mapper = Mappers.getMapper(VmClusterStateHistoryMapper.class);

    @Test
    void toDomain_null_returnsNull() {
        assertThat(mapper.toDomain(null)).isNull();
    }

    @Test
    void toEntity_null_returnsNull() {
        assertThat(mapper.toEntity(null)).isNull();
    }

    @Test
    void toDomain_mapsAllFields() {
        VmClusterStateHistoryEntity e = VmClusterStateHistoryEntity.builder()
                .id("h-1")
                .clusterName("demo-aws-01")
                .vmClusterId("vm-1")
                .fromState(VmClusterStatus.REQUESTED)
                .toState(VmClusterStatus.PROVISIONING)
                .reason("user initiated")
                .principal("alice")
                .requestId("req-xyz")
                .valid(true)
                .build();

        VmClusterStateHistory d = mapper.toDomain(e);

        assertThat(d.id()).isEqualTo("h-1");
        assertThat(d.clusterName()).isEqualTo("demo-aws-01");
        assertThat(d.fromState()).isEqualTo(VmClusterStatus.REQUESTED);
        assertThat(d.toState()).isEqualTo(VmClusterStatus.PROVISIONING);
        assertThat(d.valid()).isTrue();
        assertThat(d.isInvalidTransition()).isFalse();
    }

    @Test
    void isInvalidTransition_onlyWhenValidExplicitlyFalse() {
        VmClusterStateHistory validTrue = sample(true);
        VmClusterStateHistory validFalse = sample(false);
        VmClusterStateHistory validNull = sample(null);

        assertThat(validTrue.isInvalidTransition()).isFalse();
        assertThat(validFalse.isInvalidTransition()).isTrue();
        // null 은 invalid 로 단정 짓지 않음 — 기본 합의는 valid=true 의도.
        assertThat(validNull.isInvalidTransition()).isFalse();
    }

    @Test
    void toEntity_nullValid_defaultsToTrue() {
        VmClusterStateHistory d = sample(null);
        VmClusterStateHistoryEntity e = mapper.toEntity(d);
        assertThat(e.getValid()).isTrue();
    }

    private VmClusterStateHistory sample(Boolean valid) {
        return new VmClusterStateHistory(
                "h", "c", "vm", VmClusterStatus.REQUESTED, VmClusterStatus.PROVISIONING, "r", "p", "req", valid, null);
    }
}
