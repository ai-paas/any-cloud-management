package com.aipaas.anycloud.domain.credential;

import com.aipaas.anycloud.domain.credential.api.request.CreateCspCredentialRequest;
import com.aipaas.anycloud.domain.credential.api.response.CspCredentialResponse;
import java.util.List;
import java.util.Map;

public interface CspCredentialService {

    List<CspCredentialResponse> getCredentials();

    CspCredentialResponse getCredential(String credentialId);

    /**
     * 등록된 값을 그대로 돌려준다.
     *
     * <p>목록, 상세 응답에는 절대 섞지 않는다 — 캐시와 로그에 남는다. 전용 경로로만 꺼내고
     * 누가 언제 무엇을 봤는지 감사 로그에 남긴다.
     */
    java.util.Map<String, String> revealCredential(String credentialId);

    CspCredentialResponse createCredential(CreateCspCredentialRequest request);

    /**
     * 설명과 값만 바꾼다. 이름과 프로바이더는 바꿀 수 없다 — {@link CredentialUpdateRules} 참고.
     *
     * <p>값을 바꾸면 저장된 가용성 결과를 지운다. 그 결과는 더 이상 이 값에 대한 것이 아니다.
     */
    CspCredentialResponse updateCredential(
            String credentialId, com.aipaas.anycloud.domain.credential.api.request.UpdateCspCredentialRequest request);

    void deleteCredential(String credentialId);

    ResolvedCspCredential resolveForProvision(String provider, String credentialId);

    Map<String, String> resolveEnvironment(String provider, String credentialId);
}
