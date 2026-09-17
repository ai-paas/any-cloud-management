package com.aipaas.anycloud.domain.credential;

/** 등록된 자격증명이 실제로 쓸 수 있는지 확인. */
public interface CredentialHealthService {

    /** 사용자가 직접 누른 확인. 캐시를 건너뛴다. */
    CredentialHealth check(String provider, String credentialId);

    /** 화면 진입 시 자동 갱신. 최근에 확인했으면 저장된 값을 그대로 돌려준다. */
    CredentialHealth refreshIfStale(String provider, String credentialId);
}
