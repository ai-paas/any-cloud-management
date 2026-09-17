package com.aipaas.anycloud.domain.provisioning.convergence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipaas.anycloud.domain.provisioning.convergence.components.AgentComponent;
import com.aipaas.anycloud.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 설정 키 오타는 @Value 기본값으로 조용히 대체되므로 바인딩을 명시적으로 확인한다.
 * config/workflow.yaml 에서 키 하나를 잘못 쓰면 이 테스트만 잡을 수 있다.
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
        // consumer 스레드 점유 상한이 이 값의 존재 이유다. 횟수가 아니라 총 예산을 지킨다 —
        // 5회 x 30초 = 2분으로, 1분 간격 3회와 같은 시간에 해상도만 두 배다.
        assertThat(verifyMaxAttempts).isEqualTo(5);
        assertThat(verifyInterval).isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(verifyInterval.multipliedBy(verifyMaxAttempts - 1))
                .as("VERIFY 가 consumer 스레드를 2분 넘게 잡는다")
                .isLessThanOrEqualTo(java.time.Duration.ofMinutes(2));
    }

    @Test
    void degradedRecheckIsFasterThanTheRegularSweep() {
        // 재확인이 정기 주기보다 느리면 존재 이유가 없다.
        assertThat(degradedRecheckDelay).isLessThan(java.time.Duration.ofMillis(intervalMs));
    }

    @Test
    void agentRequirementBindsToEnum() {
        // 문자열이 enum 으로 변환되지 않으면 컨텍스트 자체가 뜨지 않는다.
        assertThat(agentRequirement).isEqualTo(Requirement.REQUIRED);
    }

    @Test
    void agentComponentIsWired() {
        assertThat(agentComponent).isNotNull();
    }
}
