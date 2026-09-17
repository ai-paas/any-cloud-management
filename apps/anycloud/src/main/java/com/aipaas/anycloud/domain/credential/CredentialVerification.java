package com.aipaas.anycloud.domain.credential;

import java.util.Locale;
import java.util.Set;

/**
 * 리전 조회가 자격증명을 실제로 검증하지 못하는 프로바이더.
 *
 * <p>가용성 확인은 리전 목록 길이로 판정한다. 대부분은 리전 조회가 곧 인증 호출이라 그것으로
 * 충분하다 — OpenStack 은 Keystone 토큰을 받고, Azure/Alibaba/GCP/OCI/DigitalOcean 은 각자
 * API 를 친다.
 *
 * <p>AWS 만 SDK 에 컴파일된 목록을 쓰고 있었다 — 완전히 가짜인 키가 '정상'으로 나왔다.
 * 지금은 DescribeRegions 를 부르므로 모든 프로바이더가 검증된다.
 *
 * <p>목록을 비워둔 것은 "해당 없음" 이지 "검사를 껐다" 가 아니다. 검증 호출이 없는 프로바이더가
 * 새로 생기면 여기에 넣어야 한다 — 검증하지 않고 정상이라 말하는 것이 가장 위험하다.
 */
public final class CredentialVerification {

    private static final Set<String> UNVERIFIABLE = Set.of();

    private CredentialVerification() {}

    public static boolean verifiable(String provider) {
        return provider != null && !UNVERIFIABLE.contains(provider.toLowerCase(Locale.ROOT));
    }
}
