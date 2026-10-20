package com.aipaas.anycloud.domain.provisioning.workflow;

/**
 * 삭제 전 확인한 CSP 의 실제 상태.
 *
 * <p>상태 변경 경로는 화면 외에도 존재. CSP 콘솔에서 VM 을 직접 삭제하거나 중지하면 기록과
 * 불일치하며, 미확인 시 그 상태로 destroy 실행.
 *
 * <p><b>확인 결과는 기록 용도이며 destroy 생략 근거가 아님.</b> outputs 가 비어 있어도 자원이
 * 없다는 뜻은 아님. up 이 중간에 실패하면 보안 그룹과 네트워크는 생성된 상태에서 outputs 는 0.
 * 이때 생략하면 해당 자원이 잔존해 공유 테넌트 쿼터 소모. destroy 는 멱등이라 대상이 없으면 즉시 종료.
 *
 * @param remaining stack outputs 기준 추정 수. 실제 자원 수 아님. 확인 실패 시 -1
 * @param note 작업 이력 기록용 한 줄. 실패 표기만으로는 사유 확인 불가
 */
public record DestroyPrecheck(int remaining, String note) {

    private static final int UNKNOWN = -1;

    /** CSP 를 실제로 확인했다. */
    public static DestroyPrecheck refreshed(int remaining) {
        return remaining == 0
                ? new DestroyPrecheck(0, "스택에 출력값이 없습니다. 만들다 만 자원이 남았을 수 있어 그대로 삭제를 진행합니다.")
                : new DestroyPrecheck(remaining, "스택 출력값 " + remaining + "개가 확인됩니다.");
    }

    /** 확인 실패 상태. 삭제는 그대로 진행. 차단 시 CSP 장애 상황에서 정리 수단 부재. */
    public static DestroyPrecheck failed(String reason) {
        return new DestroyPrecheck(UNKNOWN, "CSP 상태를 확인하지 못해 그대로 진행합니다: " + reason);
    }

    /** 확인 기능 비활성 상태. */
    public static DestroyPrecheck skipped() {
        return new DestroyPrecheck(UNKNOWN, null);
    }
}
