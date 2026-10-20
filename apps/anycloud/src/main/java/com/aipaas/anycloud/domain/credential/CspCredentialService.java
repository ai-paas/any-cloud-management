package com.aipaas.anycloud.domain.credential;

import com.aipaas.anycloud.domain.credential.api.request.CreateCspCredentialRequest;
import com.aipaas.anycloud.domain.credential.api.response.CspCredentialResponse;
import java.util.List;
import java.util.Map;

public interface CspCredentialService {

    List<CspCredentialResponse> getCredentials();

    CspCredentialResponse getCredential(String credentialId);

    /**
     * 등록된 값 그대로 반환.
     *
     * <p>목록, 상세 응답에 포함 금지. 캐시와 로그에 잔존. 전용 경로로만 조회하고 조회 주체,
     * 시각, 대상을 감사 로그에 기록.
     */
    java.util.Map<String, String> revealCredential(String credentialId);

    CspCredentialResponse createCredential(CreateCspCredentialRequest request);

    /**
     * 설명과 값만 변경. 이름과 프로바이더는 변경 불가. {@link CredentialUpdateRules} 참고.
     *
     * <p>값 변경 시 저장된 가용성 결과 삭제. 해당 결과는 변경 전 값 기준.
     */
    CspCredentialResponse updateCredential(
            String credentialId, com.aipaas.anycloud.domain.credential.api.request.UpdateCspCredentialRequest request);

    void deleteCredential(String credentialId);

    ResolvedCspCredential resolveForProvision(String provider, String credentialId);

    Map<String, String> resolveEnvironment(String provider, String credentialId);
}
