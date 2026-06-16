package com.aipaas.anycloud.domain.credential;

import com.aipaas.anycloud.domain.credential.model.CspCredentialSourceType;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ResolvedCspCredential {

    private final String credentialId;
    private final String credentialName;
    private final CspCredentialSourceType sourceType;
    private final Map<String, String> environment;

    public Map<String, String> environmentOrEmpty() {
        return environment == null ? new LinkedHashMap<>() : new LinkedHashMap<>(environment);
    }
}
