package com.aipaas.anycloud.domain.credential.internal;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.provisioning.preflight.validation.ProvisioningProviderValidator;
import com.aipaas.anycloud.domain.credential.api.request.CreateCspCredentialRequest;
import com.aipaas.anycloud.domain.credential.CspCredentialCryptoService;
import com.aipaas.anycloud.domain.credential.CspCredentialEntity;
import com.aipaas.anycloud.domain.credential.CspCredentialRepository;
import com.aipaas.anycloud.domain.credential.api.response.CspCredentialResponse;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.credential.ResolvedCspCredential;
import com.aipaas.anycloud.domain.credential.model.CspCredentialSourceType;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CspCredentialServiceImpl implements CspCredentialService {

    private final CspCredentialRepository cspCredentialRepository;
    private final VmClusterRepository vmClusterRepository;
    private final CspCredentialCryptoService cspCredentialCryptoService;
    private final ObjectMapper objectMapper;

    @Override
    public List<CspCredentialResponse> getCredentials() {
        return cspCredentialRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public CspCredentialResponse getCredential(String credentialId) {
        return toResponse(getEntity(credentialId));
    }

    @Override
    public CspCredentialResponse createCredential(CreateCspCredentialRequest request) {
        SupportedProvisioningProvider provider = SupportedProvisioningProvider.from(request.getProvider());
        CspCredentialSourceType sourceType =
                request.getSourceType() == null ? CspCredentialSourceType.MANUAL : request.getSourceType();

        Map<String, String> payload = new LinkedHashMap<>();
        String encryptedPayload = null;
        List<String> credentialKeys;

        if (sourceType == CspCredentialSourceType.ENV) {
            ProvisioningProviderValidator.validateCredentialValues(provider, Map.of());
            credentialKeys = ProvisioningProviderValidator.requiredCredentialKeys(provider);
        } else {
            payload.putAll(request.getCredentials() == null ? Map.of() : request.getCredentials());
            ProvisioningProviderValidator.validateCredentialValues(provider, payload);
            encryptedPayload = cspCredentialCryptoService.encrypt(writeJson(payload));
            credentialKeys = payload.keySet().stream().sorted().toList();
        }

        CspCredentialEntity entity = CspCredentialEntity.builder()
                .provider(provider.getCanonicalName())
                .name(request.getName())
                .description(request.getDescription())
                .sourceType(sourceType)
                .encryptedPayload(encryptedPayload)
                .credentialKeys(writeJson(credentialKeys))
                .active(true)
                .build();

        return toResponse(cspCredentialRepository.save(entity));
    }

    @Override
    public void deleteCredential(String credentialId) {
        if (vmClusterRepository.countByCredentialIdAndActiveRequestKeyIsNotNull(credentialId) > 0) {
            throw new CustomException("Credential is still referenced by active VM clusters", ErrorCode.DATA_INTEGRITY);
        }
        cspCredentialRepository.delete(getEntity(credentialId));
    }

    @Override
    public ResolvedCspCredential resolveForProvision(String provider, String credentialId) {
        SupportedProvisioningProvider normalizedProvider = SupportedProvisioningProvider.from(provider);

        if (credentialId != null && !credentialId.isBlank()) {
            CspCredentialEntity entity = getEntity(credentialId);
            assertProviderMatches(normalizedProvider, entity.getProvider());
            return ResolvedCspCredential.builder()
                    .credentialId(entity.getId())
                    .credentialName(entity.getName())
                    .sourceType(entity.getSourceType())
                    .environment(resolveEnvironment(entity.getProvider(), entity.getId(), entity.getSourceType()))
                    .build();
        }

        ProvisioningProviderValidator.validateCredentialValues(normalizedProvider, Map.of());
        return ResolvedCspCredential.builder()
                .credentialId(null)
                .credentialName("Application Environment")
                .sourceType(CspCredentialSourceType.ENV)
                .environment(Map.of())
                .build();
    }

    @Override
    public Map<String, String> resolveEnvironment(
            String provider, String credentialId, CspCredentialSourceType sourceType) {
        SupportedProvisioningProvider normalizedProvider = SupportedProvisioningProvider.from(provider);
        if (sourceType == null || sourceType == CspCredentialSourceType.ENV) {
            ProvisioningProviderValidator.validateCredentialValues(normalizedProvider, Map.of());
            return Map.of();
        }

        if (credentialId == null || credentialId.isBlank()) {
            throw new CustomException(
                    "Credential ID is required for stored MANUAL credentials", ErrorCode.INVALID_INPUT_VALUE);
        }

        CspCredentialEntity entity = getEntity(credentialId);
        assertProviderMatches(normalizedProvider, entity.getProvider());
        String decrypted = cspCredentialCryptoService.decrypt(entity.getEncryptedPayload());
        Map<String, String> payload = readJsonMap(decrypted);
        ProvisioningProviderValidator.validateCredentialValues(normalizedProvider, payload);
        return payload;
    }

    private CspCredentialEntity getEntity(String credentialId) {
        return cspCredentialRepository
                .findById(credentialId)
                .orElseThrow(() -> new CustomException("Credential not found: " + credentialId, ErrorCode.NOT_FOUND));
    }

    private void assertProviderMatches(SupportedProvisioningProvider provider, String entityProvider) {
        if (!provider.getCanonicalName().equalsIgnoreCase(entityProvider)) {
            // Provider mismatch 는 영구 입력 오류 — async workflow 가 동일 (request, credential) 로
            // 재시도해도 동일 결과. PermanentProvisioningFailure 는 RabbitMQ retry interceptor 가
            // 즉시 DLQ 로 라우팅 (ExceptionClassifierRetryPolicy → NeverRetryPolicy).
            throw new com.aipaas.anycloud.common.error.exception.provisioning.PermanentProvisioningFailure(
                    "Credential provider does not match requested VM provider", ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private CspCredentialResponse toResponse(CspCredentialEntity entity) {
        return CspCredentialResponse.builder()
                .id(entity.getId())
                .provider(entity.getProvider())
                .name(entity.getName())
                .description(entity.getDescription())
                .sourceType(entity.getSourceType())
                .active(entity.getActive())
                .credentialKeys(readJsonList(entity.getCredentialKeys()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private Map<String, String> readJsonMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new CustomException("Failed to parse credential payload", ErrorCode.RUNTIME_EXCEPTION);
        }
    }

    private List<String> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new CustomException("Failed to parse credential keys", ErrorCode.RUNTIME_EXCEPTION);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CustomException("Failed to serialize credential payload", ErrorCode.RUNTIME_EXCEPTION);
        }
    }
}
