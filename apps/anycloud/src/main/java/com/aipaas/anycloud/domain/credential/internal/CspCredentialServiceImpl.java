package com.aipaas.anycloud.domain.credential.internal;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.audit.Audited;
import com.aipaas.anycloud.domain.credential.CredentialUpdateRules;
import com.aipaas.anycloud.domain.credential.CspCredentialCryptoService;
import com.aipaas.anycloud.domain.credential.CspCredentialEntity;
import com.aipaas.anycloud.domain.credential.CspCredentialRepository;
import com.aipaas.anycloud.domain.credential.CspCredentialService;
import com.aipaas.anycloud.domain.credential.ResolvedCspCredential;
import com.aipaas.anycloud.domain.credential.api.request.CreateCspCredentialRequest;
import com.aipaas.anycloud.domain.credential.api.request.UpdateCspCredentialRequest;
import com.aipaas.anycloud.domain.credential.api.response.CspCredentialResponse;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.aipaas.anycloud.domain.provisioning.model.SupportedProvisioningProvider;
import com.aipaas.anycloud.domain.provisioning.preflight.validation.ProvisioningProviderValidator;
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
        // 모든 credential 은 encrypt 경로로 통일 — 과거 ENV 분기 (sourceType) 제거됨.
        Map<String, String> payload =
                new LinkedHashMap<>(request.getCredentials() == null ? Map.of() : request.getCredentials());
        ProvisioningProviderValidator.validateCredentialValues(provider, payload);
        // DB 제약이 먼저 터지면 SQL 오류가 그대로 올라가 무엇이 문제인지 알 수 없다.
        if (cspCredentialRepository.existsByProviderAndName(provider.getCanonicalName(), request.getName())) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "name",
                    request.getName(),
                    provider.getCanonicalName() + " 에 같은 이름의 자격증명이 이미 있습니다.");
        }
        String encryptedPayload = cspCredentialCryptoService.encrypt(writeJson(payload));
        List<String> credentialKeys = payload.keySet().stream().sorted().toList();

        CspCredentialEntity entity = CspCredentialEntity.builder()
                .provider(provider.getCanonicalName())
                .name(request.getName())
                .description(request.getDescription())
                .encryptedPayload(encryptedPayload)
                .credentialKeys(writeJson(credentialKeys))
                .active(true)
                .build();

        return toResponse(cspCredentialRepository.save(entity));
    }

    @Override
    @Audited(
            action = "credential.update",
            resourceType = "credential",
            resourceId = "#credentialId",
            // 무엇으로 바꿨는지는 남기지 않는다. 남기면 감사 로그가 비밀 저장소가 된다.
            summary = "'updated'")
    public CspCredentialResponse updateCredential(String credentialId, UpdateCspCredentialRequest request) {
        CspCredentialEntity entity = getEntity(credentialId);

        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription());
        }

        Map<String, String> values = request.getCredentials();
        if (CredentialUpdateRules.replacesValues(values)) {
            if (CredentialUpdateRules.hasBlankValue(values)) {
                throw new CustomException(ErrorCode.INVALID_INPUT_VALUE, "credentials", null, "빈 값으로는 바꿀 수 없습니다.");
            }
            SupportedProvisioningProvider provider = SupportedProvisioningProvider.from(entity.getProvider());
            Map<String, String> payload = new LinkedHashMap<>(values);
            ProvisioningProviderValidator.validateCredentialValues(provider, payload);

            entity.setEncryptedPayload(cspCredentialCryptoService.encrypt(writeJson(payload)));
            entity.setCredentialKeys(
                    writeJson(payload.keySet().stream().sorted().toList()));
        }

        if (CredentialUpdateRules.shouldRecheckHealth(values)) {
            // 저장된 결과는 더 이상 이 값에 대한 것이 아니다. 남겨두면 바뀐 키가 틀려도 정상으로 보인다.
            entity.setHealthStatus(null);
            entity.setHealthKind(null);
            entity.setHealthDetail(null);
            entity.setHealthCheckedAt(null);
        }

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
        // credentialId 필수 — 과거 ENV fallback 제거. 자격증명 등록을 사용자 명시 작업으로 강제.
        if (credentialId == null || credentialId.isBlank()) {
            throw new CustomException("Credential ID is required for VM provisioning", ErrorCode.INVALID_INPUT_VALUE);
        }
        CspCredentialEntity entity = getEntity(credentialId);
        assertProviderMatches(normalizedProvider, entity.getProvider());
        return ResolvedCspCredential.builder()
                .credentialId(entity.getId())
                .credentialName(entity.getName())
                .environment(resolveEnvironment(entity.getProvider(), entity.getId()))
                .build();
    }

    @Override
    @Audited(
            action = "credential.reveal",
            resourceType = "credential",
            resourceId = "#credentialId",
            // summary 에 #result 를 넣으면 감사 로그가 비밀 저장소가 된다. 어떤 키를 봤는지만 남긴다.
            summary = "'revealed'")
    public Map<String, String> revealCredential(String credentialId) {
        CspCredentialEntity entity = getEntity(credentialId);
        return readJsonMap(cspCredentialCryptoService.decrypt(entity.getEncryptedPayload()));
    }

    @Override
    public Map<String, String> resolveEnvironment(String provider, String credentialId) {
        SupportedProvisioningProvider normalizedProvider = SupportedProvisioningProvider.from(provider);
        if (credentialId == null || credentialId.isBlank()) {
            throw new CustomException("Credential ID is required", ErrorCode.INVALID_INPUT_VALUE);
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
                .active(entity.getActive())
                .credentialKeys(readJsonList(entity.getCredentialKeys()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .healthStatus(entity.getHealthStatus())
                .healthKind(entity.getHealthKind())
                .healthDetail(entity.getHealthDetail())
                .healthCheckedAt(entity.getHealthCheckedAt())
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
