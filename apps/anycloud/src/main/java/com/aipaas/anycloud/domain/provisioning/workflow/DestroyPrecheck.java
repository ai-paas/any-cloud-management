package com.aipaas.anycloud.domain.provisioning.workflow;

/**
 * 지우기 전에 확인한 CSP 의 실제 상태.
 *
 * <p>화면에서만 상태가 바뀌는 것이 아니다. 누가 CSP 콘솔에서 직접 VM 을 지우거나 끄면 우리 기록과
 * 어긋나는데, 확인하지 않으면 그걸 모른 채 destroy 를 돌린다.
 *
 * <p><b>확인은 기록일 뿐 destroy 를 건너뛰는 근거가 아니다.</b> outputs 가 비었다고 자원이 없는
 * 것이 아니다 — up 이 중간에 실패하면 보안 그룹과 네트워크는 만들어졌는데 outputs 는 0 이다.
 * 그때 건너뛰면 그 자원들이 남아 공유 테넌트의 쿼터를 문다. destroy 는 멱등이라 없으면 금방 끝난다.
 *
 * @param remaining stack outputs 로 짐작한 수. 자원 수가 아니다. 확인하지 못했으면 -1
 * @param note 작업 이력에 남길 한 줄. '실패' 만 남으면 왜인지 알 수 없다
 */
public record DestroyPrecheck(int remaining, String note) {

    private static final int UNKNOWN = -1;

    /** CSP 를 실제로 확인했다. */
    public static DestroyPrecheck refreshed(int remaining) {
        return remaining == 0
                ? new DestroyPrecheck(0, "스택에 출력값이 없습니다. 만들다 만 자원이 남았을 수 있어 그대로 삭제를 진행합니다.")
                : new DestroyPrecheck(remaining, "스택 출력값 " + remaining + "개가 확인됩니다.");
    }

    /** 확인하지 못했다. 삭제는 그대로 진행한다 — 막으면 CSP 장애 때 정리할 방법이 없어진다. */
    public static DestroyPrecheck failed(String reason) {
        return new DestroyPrecheck(UNKNOWN, "CSP 상태를 확인하지 못해 그대로 진행합니다: " + reason);
    }

    /** 확인 기능이 꺼져 있다. */
    public static DestroyPrecheck skipped() {
        return new DestroyPrecheck(UNKNOWN, null);
    }
}
