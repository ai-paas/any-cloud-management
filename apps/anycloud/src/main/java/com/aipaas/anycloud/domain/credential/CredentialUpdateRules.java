package com.aipaas.anycloud.domain.credential;

import java.util.Map;

/**
 * 자격증명 수정에서 무엇을 바꿔도 되는지.
 *
 * <p>이름과 프로바이더는 막는다 — {@code vm_cluster.credential_name} 이 프로비저닝 당시 이름을
 * 기록으로 들고 있고, 자격증명이 지워진 뒤에는 그것이 유일한 흔적이다. 프로바이더가 바뀌면
 * 키 구성 자체가 달라져 새로 등록하는 것과 다르지 않다.
 */
public final class CredentialUpdateRules {

    private CredentialUpdateRules() {}

    /**
     * 값은 통째로 바꾼다. 부분 수정은 AWS 의 ID/시크릿처럼 짝이 있는 키에서 못 쓰는 조합을 만들고,
     * 병합하려면 평문이 서버 메모리에 더 오래 머문다.
     */
    public static boolean replacesValues(Map<String, String> values) {
        return values != null && !values.isEmpty();
    }

    /** 빈 값으로 덮으면 조용히 못 쓰는 자격증명이 된다. */
    public static boolean hasBlankValue(Map<String, String> values) {
        if (values == null) {
            return false;
        }
        return values.values().stream().anyMatch(v -> v == null || v.isBlank());
    }

    /** 값을 바꿨으면 저장된 가용성 결과는 더 이상 그 값에 대한 것이 아니다. */
    public static boolean shouldRecheckHealth(Map<String, String> values) {
        return replacesValues(values);
    }
}
