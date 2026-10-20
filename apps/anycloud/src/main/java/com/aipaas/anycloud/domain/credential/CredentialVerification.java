package com.aipaas.anycloud.domain.credential;

import java.util.Locale;
import java.util.Set;

/**
 * 리전 조회가 자격증명을 실제로 검증하지 못하는 프로바이더.
 *
 * <p>가용성 확인은 리전 목록 길이로 판정. 대부분 리전 조회가 인증 호출을 겸함. OpenStack 은
 * Keystone 토큰을 발급받고 Alibaba, GCP, OCI 는 각자 API 를 호출.
 *
 * <p>AWS 만 SDK 컴파일 목록을 사용해 유효하지 않은 키도 정상으로 판정. 현재는 DescribeRegions
 * 를 호출하므로 전체 프로바이더 검증.
 *
 * <p>목록이 빈 것은 해당 없음이며 검사 비활성화가 아님. 검증 호출이 없는 프로바이더 추가 시
 * 여기에 등록 필요. 미검증 상태의 정상 판정이 최대 위험.
 */
public final class CredentialVerification {

    private static final Set<String> UNVERIFIABLE = Set.of();

    private CredentialVerification() {}

    public static boolean verifiable(String provider) {
        return provider != null && !UNVERIFIABLE.contains(provider.toLowerCase(Locale.ROOT));
    }
}
