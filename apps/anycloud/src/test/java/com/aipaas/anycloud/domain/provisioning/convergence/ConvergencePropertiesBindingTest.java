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

    @Value("${anycloud.vm-cluster.component.agent.requirement}")
    Requirement agentRequirement;

    @Test
    void convergenceScheduleIsExplicitlyConfigured() {
        assertThat(intervalMs).isEqualTo(300_000L);
        assertThat(initialDelayMs).isEqualTo(60_000L);
    }

    @Test
    void verifyBudgetStaysShort() {
        // consumer 스레드 점유 상한이 이 값의 존재 이유다. 늘리려면 근거가 필요하다.
        assertThat(verifyMaxAttempts).isEqualTo(3);
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
