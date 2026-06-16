package com.aipaas.anycloud.domain.credential;

import com.aipaas.anycloud.domain.credential.api.request.CreateCspCredentialRequest;
import com.aipaas.anycloud.domain.credential.api.response.CspCredentialResponse;
import com.aipaas.anycloud.domain.credential.model.CspCredentialSourceType;
import java.util.List;
import java.util.Map;

public interface CspCredentialService {

    List<CspCredentialResponse> getCredentials();

    CspCredentialResponse getCredential(String credentialId);

    CspCredentialResponse createCredential(CreateCspCredentialRequest request);

    void deleteCredential(String credentialId);

    ResolvedCspCredential resolveForProvision(String provider, String credentialId);

    Map<String, String> resolveEnvironment(String provider, String credentialId, CspCredentialSourceType sourceType);
}
