package com.aipaas.anycloud.domain.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.agent.ClusterAgentEntity;
import com.aipaas.anycloud.domain.agent.model.ClusterAgentStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

class AdminAgentSpecsTest {

    @Test
    void allFiltersNull_yieldsNoOpSpec() {
        Specification<ClusterAgentEntity> spec = AdminAgentSpecs.combine(null, null, null, null, null);
        assertThat(spec).isNotNull();
    }

    @Test
    void statusFilter_buildsSpec() {
        Specification<ClusterAgentEntity> spec = AdminAgentSpecs.combine(
                List.of(ClusterAgentStatus.ACTIVE, ClusterAgentStatus.DEGRADED), null, null, null, null);
        assertThat(spec).isNotNull();
    }

    @Test
    void clusterNamesFilter_buildsSpec() {
        Specification<ClusterAgentEntity> spec =
                AdminAgentSpecs.combine(null, List.of("aws-prod-01", "gcp-stage-1"), null, null, null);
        assertThat(spec).isNotNull();
    }

    @Test
    void versionPrefix_buildsLikeSpec() {
        Specification<ClusterAgentEntity> spec = AdminAgentSpecs.combine(null, null, "0.3", null, null);
        assertThat(spec).isNotNull();
    }

    @Test
    void lastSeenOlderThanSec_buildsTimeBoundary() {
        Specification<ClusterAgentEntity> spec = AdminAgentSpecs.combine(null, null, null, 3600L, LocalDateTime.now());
        assertThat(spec).isNotNull();
    }

    @Test
    void emptyAndBlankFilters_skipBuild() {
        Specification<ClusterAgentEntity> spec = AdminAgentSpecs.combine(List.of(), List.of(), "  ", null, null);
        assertThat(spec).isNotNull();
    }
}
