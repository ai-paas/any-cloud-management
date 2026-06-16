package com.aipaas.anycloud.domain.credential.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.credential.api.request.CreateCspCredentialRequest;
import com.aipaas.anycloud.domain.credential.CspCredentialCryptoService;
import com.aipaas.anycloud.domain.credential.CspCredentialEntity;
import com.aipaas.anycloud.domain.credential.CspCredentialRepository;
import com.aipaas.anycloud.domain.credential.api.response.CspCredentialResponse;
import com.aipaas.anycloud.domain.credential.model.CspCredentialSourceType;
import com.aipaas.anycloud.domain.provisioning.VmClusterRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * {@link CspCredentialServiceImpl} 회귀 lock —.
 *
 * <p>MANUAL credential lifecycle 보안 critical:
 * <ul>
 *   <li>MANUAL credential 은 항상 encryption 거쳐 저장 (plaintext 저장 회귀 방지)</li>
 *   <li>provider mismatch 시 사용 차단 — AWS credential 로 GCP cluster provision 시도 거부</li>
 *   <li>active cluster 가 참조 중인 credential 삭제 거부 — orphan provisioning 방지</li>
 *   <li>MANUAL 인데 credentialId 없으면 거부 — silent ENV fallback 방지</li>
 * </ul>
 *
 * <p>ENV source type 경로는 {@code System.getenv()} 호출 의존이라 본 unit test 범위 외 — integration
 * test 또는 ENV 변수 주입 환경에서 검증.
 */
class CspCredentialServiceImplTest {

    private CspCredentialRepository repository;
    private VmClusterRepository vmClusterRepository;
    private CspCredentialCryptoService crypto;
    private ObjectMapper objectMapper;
    private CspCredentialServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        repository = Mockito.mock(CspCredentialRepository.class);
        vmClusterRepository = Mockito.mock(VmClusterRepository.class);
        crypto = Mockito.mock(CspCredentialCryptoService.class);
        // Mock ObjectMapper — Jackson 의 BufferRecycler 가 classpath 의 jackson-core
        // 버전과 mismatch 나는 환경 회피. test 에서는 실제 JSON 출력 의미 없음.
        objectMapper = Mockito.mock(ObjectMapper.class);
        Mockito.lenient().when(objectMapper.writeValueAsString(Mockito.any())).thenReturn("[\"k\"]");
        Mockito.lenient()
                .when(objectMapper.readValue(
                        Mockito.anyString(), Mockito.any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(List.of("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY"));
        service = new CspCredentialServiceImpl(repository, vmClusterRepository, crypto, objectMapper);
    }

    // ============================================================================
    // createCredential — MANUAL flow (encryption 거침)
    // ============================================================================

    @Test
    void createCredential_manual_encryptsPayloadBeforeStore() {
        CreateCspCredentialRequest req = new CreateCspCredentialRequest();
        req.setProvider("aws");
        req.setName("my-aws");
        req.setSourceType(CspCredentialSourceType.MANUAL);
        req.setCredentials(Map.of(
                "AWS_ACCESS_KEY_ID", "AKIA...",
                "AWS_SECRET_ACCESS_KEY", "wJalr..."));

        when(crypto.encrypt(anyString())).thenReturn("iv:cipher");
        when(repository.save(any(CspCredentialEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createCredential(req);

        // 회귀 lock — encrypt 가 반드시 호출되어야 함.
        verify(crypto).encrypt(anyString());

        // entity 의 encryptedPayload 가 encrypt 의 출력값으로 세팅됨.
        ArgumentCaptor<CspCredentialEntity> entityCaptor = ArgumentCaptor.forClass(CspCredentialEntity.class);
        verify(repository).save(entityCaptor.capture());
        CspCredentialEntity saved = entityCaptor.getValue();
        assertThat(saved.getEncryptedPayload())
                .as("plaintext 저장 회귀 방지 — 항상 crypto 출력값")
                .isEqualTo("iv:cipher");
        assertThat(saved.getProvider()).isEqualTo("AWS");
        assertThat(saved.getSourceType()).isEqualTo(CspCredentialSourceType.MANUAL);
        assertThat(saved.getActive()).isTrue();
    }

    @Test
    void createCredential_provider_normalizedToCanonical() {
        // "googlecloud" alias → "GCP" canonical 저장.
        CreateCspCredentialRequest req = new CreateCspCredentialRequest();
        req.setProvider("googlecloud");
        req.setName("my-gcp");
        req.setSourceType(CspCredentialSourceType.MANUAL);
        req.setCredentials(Map.of("GOOGLE_CREDENTIALS", "{\"type\":\"service_account\"}"));

        when(crypto.encrypt(anyString())).thenReturn("enc");
        when(repository.save(any(CspCredentialEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createCredential(req);

        ArgumentCaptor<CspCredentialEntity> captor = ArgumentCaptor.forClass(CspCredentialEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getProvider()).isEqualTo("GCP");
    }

    // ============================================================================
    // deleteCredential — active cluster reference 거부
    // ============================================================================

    @Test
    void deleteCredential_activeReference_throws_noDelete() {
        // 회귀 lock — credential 삭제 시 active VM cluster 가 참조 중이면 orphan provisioning 발생.
        when(vmClusterRepository.countByCredentialIdAndActiveRequestKeyIsNotNull("cred-1"))
                .thenReturn(2L);

        assertThatThrownBy(() -> service.deleteCredential("cred-1"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("referenced by active VM clusters");

        verify(repository, never()).delete(any(CspCredentialEntity.class));
    }

    @Test
    void deleteCredential_noActiveReference_deletes() {
        when(vmClusterRepository.countByCredentialIdAndActiveRequestKeyIsNotNull("cred-1"))
                .thenReturn(0L);
        CspCredentialEntity entity = CspCredentialEntity.builder()
                .id("cred-1")
                .provider("AWS")
                .name("my-aws")
                .sourceType(CspCredentialSourceType.MANUAL)
                .build();
        when(repository.findById("cred-1")).thenReturn(Optional.of(entity));

        service.deleteCredential("cred-1");

        verify(repository).delete(entity);
    }

    @Test
    void deleteCredential_credentialMissing_throws() {
        when(vmClusterRepository.countByCredentialIdAndActiveRequestKeyIsNotNull("missing"))
                .thenReturn(0L);
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteCredential("missing"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("Credential not found");
    }

    // ============================================================================
    // resolveForProvision — provider mismatch 거부
    // ============================================================================

    @Test
    void resolveForProvision_providerMismatch_throws() {
        // 회귀 lock — AWS credential 로 GCP cluster provision 시도 거부 (cross-provider abuse 방지).
        CspCredentialEntity awsEntity = CspCredentialEntity.builder()
                .id("cred-1")
                .provider("AWS")
                .name("aws-cred")
                .sourceType(CspCredentialSourceType.MANUAL)
                .encryptedPayload("iv:enc")
                .build();
        when(repository.findById("cred-1")).thenReturn(Optional.of(awsEntity));

        assertThatThrownBy(() -> service.resolveForProvision("gcp", "cred-1"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("provider does not match");

        // 거부됐으면 decrypt 도 호출 안 됨.
        verify(crypto, never()).decrypt(anyString());
    }

    // ============================================================================
    // resolveEnvironment — MANUAL 인데 credentialId 없으면 거부
    // ============================================================================

    @Test
    void resolveEnvironment_manualWithoutCredentialId_throws() {
        // 회귀 lock — MANUAL 인데 credentialId null 이면 silent ENV fallback 하면 안 됨.
        assertThatThrownBy(() -> service.resolveEnvironment("aws", null, CspCredentialSourceType.MANUAL))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("Credential ID is required");
    }

    @Test
    void resolveEnvironment_manualWithBlankCredentialId_throws() {
        assertThatThrownBy(() -> service.resolveEnvironment("aws", "  ", CspCredentialSourceType.MANUAL))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("Credential ID is required");
    }

    // ============================================================================
    // getCredential / getCredentials — mapping
    // ============================================================================

    @Test
    void getCredential_existing_mapsAllFields() {
        java.time.LocalDateTime created = java.time.LocalDateTime.of(2026, 6, 1, 10, 0);
        CspCredentialEntity entity = CspCredentialEntity.builder()
                .id("cred-1")
                .provider("AWS")
                .name("aws-prod")
                .description("production keys")
                .sourceType(CspCredentialSourceType.MANUAL)
                .active(true)
                .credentialKeys("[\"AWS_ACCESS_KEY_ID\",\"AWS_SECRET_ACCESS_KEY\"]")
                .createdAt(created)
                .updatedAt(created)
                .build();
        when(repository.findById("cred-1")).thenReturn(Optional.of(entity));

        CspCredentialResponse dto = service.getCredential("cred-1");

        assertThat(dto.getId()).isEqualTo("cred-1");
        assertThat(dto.getProvider()).isEqualTo("AWS");
        assertThat(dto.getName()).isEqualTo("aws-prod");
        assertThat(dto.getDescription()).isEqualTo("production keys");
        assertThat(dto.getSourceType()).isEqualTo(CspCredentialSourceType.MANUAL);
        assertThat(dto.getActive()).isTrue();
        assertThat(dto.getCredentialKeys()).containsExactly("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY");
    }

    @Test
    void getCredentials_returnsAllOrderedByCreatedAtDesc() {
        CspCredentialEntity newer = CspCredentialEntity.builder()
                .id("cred-2")
                .provider("AWS")
                .name("newer")
                .sourceType(CspCredentialSourceType.MANUAL)
                .active(true)
                .build();
        CspCredentialEntity older = CspCredentialEntity.builder()
                .id("cred-1")
                .provider("AWS")
                .name("older")
                .sourceType(CspCredentialSourceType.MANUAL)
                .active(true)
                .build();
        // repository 가 이미 ordered list 반환.
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(newer, older));

        List<CspCredentialResponse> result = service.getCredentials();

        assertThat(result).extracting(CspCredentialResponse::getId).containsExactly("cred-2", "cred-1");
    }

    @Test
    void getCredential_missing_throws() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCredential("missing"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("not found");
    }
}
