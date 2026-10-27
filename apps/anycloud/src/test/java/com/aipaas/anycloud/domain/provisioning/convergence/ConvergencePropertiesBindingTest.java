package com.aipaas.anycloud.domain.provisioning.convergence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.convergence.components.AgentComponent;
import com.aipaas.anycloud.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 설정 키 오타는 @Value 기본값으로 무음 대체되므로 바인딩을 명시적으로 검증.
 * config/workflow.yaml 의 키 오타는 이 테스트에서만 검출 가능.
 */
class ConvergencePropertiesBindingTest extends AbstractIntegrationTest {

    @Autowired
    AgentComponent agentComponent;

    @Value("${anycloud.vm-cluster.convergence.interval-ms}")
    long intervalMs;

    @Value("${anycloud.vm-cluster.convergence.initial-delay-ms}")
    long initialDelayMs;

    @Value("${anycloud.vm-cluster.convergence.verify-max-attempts}")
    int verifyMaxAttempts;

    @Value("${anycloud.vm-cluster.convergence.verify-interval}")
    java.time.Duration verifyInterval;

    @Value("${anycloud.vm-cluster.convergence.degraded-recheck-delay}")
    java.time.Duration degradedRecheckDelay;

    @Value("${anycloud.vm-cluster.component.agent.requirement}")
    Requirement agentRequirement;

    @Test
    void convergenceScheduleIsExplicitlyConfigured() {
        assertThat(intervalMs).isEqualTo(300_000L);
        assertThat(initialDelayMs).isEqualTo(60_000L);
    }

    @Test
    void verifyBudgetStaysShort() {
        /*
         * 예산을 올리면 READY 도달이 느려진다. 이 루프는 관측만 하고, 수렴을 전진시키는
         * orchestrator.driveOne 은 DEGRADED 전이 뒤 ClusterDegradedNudge 에서 호출되기 때문이다.
         * 실측(7종 동시 생성): 2분이면 전원 READY 까지 8분 31초, 10분으로 올리면 15분 44초.
         */
        assertThat(verifyMaxAttempts).isEqualTo(5);
        assertThat(verifyInterval).isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(verifyInterval.multipliedBy(verifyMaxAttempts - 1))
                .as("예산을 늘리면 DEGRADED 전이가 늦어져 수렴 구동도 그만큼 늦어진다")
                .isLessThanOrEqualTo(java.time.Duration.ofMinutes(2));
    }

    @Test
    void degradedRecheckIsFasterThanTheRegularSweep() {
        // 재확인 주기가 정기 주기보다 길면 기능 의미 상실.
        assertThat(degradedRecheckDelay).isLessThan(java.time.Duration.ofMillis(intervalMs));
    }

    @Test
    void agentRequirementBindsToEnum() {
        // 문자열의 enum 변환 실패 시 컨텍스트 기동 불가.
        assertThat(agentRequirement).isEqualTo(Requirement.REQUIRED);
    }

    @Test
    void agentComponentIsWired() {
        assertThat(agentComponent).isNotNull();
    }
}
