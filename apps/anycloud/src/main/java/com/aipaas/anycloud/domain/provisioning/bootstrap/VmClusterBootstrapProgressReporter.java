package com.aipaas.anycloud.domain.provisioning.bootstrap;

/**
 * Bootstrap 내부 sub-step 진행률을 OperationService 에 전파.
 *
 * <p>WorkflowSupportService 가 PROVISION(5%), BOOTSTRAP(33%), VERIFY(90%) 만 기록하므로
 * BOOTSTRAP 단계(20~30분) 동안 진행률이 33% 에 고정 표시. 이 reporter 는 BOOTSTRAP 의
 * 33~64 구간에서 sub-step 별 percent 를 갱신.
 *
 * <p>Bootstrap 사이클 6단계. 각 단계 시작 시점에 caller 가 phase 와 함께 호출.
 * 미실행 단계(예: HA 가 아니면 EXTRA_MASTER_JOIN 생략)는 reporter 호출도 생략.
 *
 * <p>enum 선언 순서, VmClusterBootstrapServiceImpl 의 호출 순서, percent 오름차순이 모두 일치
 * 해야 함. 하나라도 어긋나면 진행률 역행. 선언 순서 변경 시 percent 도 함께 조정.
 *
 * <p>예외, 장애 시 best-effort. 이 reporter 내부 오류는 bootstrap 흐름을 중단시키지 않음.
 */
public interface VmClusterBootstrapProgressReporter {

    void reportSubStepStart(String clusterName, BootstrapSubStep subStep);

    /**
     * Bootstrap sub-step. Percent 는 BOOTSTRAP(33) 과 VERIFY(90) 사이 33~65 범위에 매핑.
     * 사용자가 운영 화면에서 보는 stepLabel 은 enum name() 로 노출됨.
     */
    enum BootstrapSubStep {
        NODE_PREPARATION("BOOTSTRAP_NODE_PREPARATION", 35),
        MASTER_INIT("BOOTSTRAP_MASTER_INIT", 42),
        EXTRA_MASTER_JOIN("BOOTSTRAP_EXTRA_MASTER_JOIN", 48),
        WORKER_JOIN("BOOTSTRAP_WORKER_JOIN", 54),
        ADDONS("BOOTSTRAP_ADDONS", 60),
        NODES_READY("BOOTSTRAP_NODES_READY", 64);

        private final String label;
        private final int percent;

        BootstrapSubStep(String label, int percent) {
            this.label = label;
            this.percent = percent;
        }

        public String label() {
            return label;
        }

        public int percent() {
            return percent;
        }
    }
}
