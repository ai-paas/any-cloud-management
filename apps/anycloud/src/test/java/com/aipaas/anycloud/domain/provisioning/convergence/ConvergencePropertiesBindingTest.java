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
    void verifyBudgetIsBounded() {
        /*
         * 상한이 있다는 사실이 이 테스트의 요점이다. 횟수가 아니라 총 예산을 지킨다.
         *
         * 2분이던 상한은 애드온 설치를 못 기다렸다. kube-prometheus-stack 이 준비되기까지
         * 4~6분이 걸려 7종 동시 생성에서 전부 DEGRADED 를 거친 뒤 조정 루프가 뒤늦게 READY 로
         * 올렸고, 정상 생성이 화면에서 고장으로 보였다.
         *
         * 점유 대상은 AMQP consumer 가 아니라 bootstrap 풀 스레드다(listener 가 받아 풀로 넘긴다).
         * 그 풀은 한 단계가 최대 170분 점유하는 것을 전제로 잡혀 있어(AsyncProperties) 10분은
         * 새로운 위험이 아니다. 수렴 루프는 SATISFIED 즉시 반환하므로 정상 클러스터는 상한과
         * 무관하다.
         */
        assertThat(verifyMaxAttempts).isEqualTo(20);
        assertThat(verifyInterval).isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(verifyInterval.multipliedBy(verifyMaxAttempts - 1))
                .as("VERIFY 가 bootstrap 풀 스레드를 10분 넘게 잡는다")
                .isLessThanOrEqualTo(java.time.Duration.ofMinutes(10));
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
