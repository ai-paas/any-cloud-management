package com.aipaas.anycloud.domain.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.credential.internal.CspCredentialServiceImpl;
import com.aipaas.anycloud.testsupport.AbstractUnitTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 등록된 자격증명 값 조회.
 *
 * <p>값의 목록 포함 금지. 캐시와 로그에 잔존. 전용 경로로만 조회하고 조회 주체, 시각, 대상을
 * 감사 로그에 기록.
 */
class CredentialRevealTest extends AbstractUnitTest {

    private CspCredentialEntity entity() {
        CspCredentialEntity e = new CspCredentialEntity();
        e.setId("cred-1");
        e.setProvider("AWS");
        e.setName("aws-1");
        e.setEncryptedPayload("enc");
        return e;
    }

    private CspCredentialServiceImpl service(CspCredentialRepository repo, CspCredentialCryptoService crypto) {
        return new CspCredentialServiceImpl(
                repo,
                org.mockito.Mockito.mock(com.aipaas.anycloud.domain.provisioning.VmClusterRepository.class),
                crypto,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void returnsTheStoredValues() {
        CspCredentialRepository repo = org.mockito.Mockito.mock(CspCredentialRepository.class);
        CspCredentialCryptoService crypto = org.mockito.Mockito.mock(CspCredentialCryptoService.class);
        when(repo.findById("cred-1")).thenReturn(Optional.of(entity()));
        when(crypto.decrypt(anyString()))
                .thenReturn("{\"AWS_ACCESS_KEY_ID\":\"AKIAX\",\"AWS_SECRET_ACCESS_KEY\":\"s3cr3t\"}");

        assertThat(service(repo, crypto).revealCredential("cred-1"))
                .containsEntry("AWS_ACCESS_KEY_ID", "AKIAX")
                .containsEntry("AWS_SECRET_ACCESS_KEY", "s3cr3t");
    }

    @Test
    void unknownCredentialFails() {
        CspCredentialRepository repo = org.mockito.Mockito.mock(CspCredentialRepository.class);
        CspCredentialCryptoService crypto = org.mockito.Mockito.mock(CspCredentialCryptoService.class);
        when(repo.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(repo, crypto).revealCredential("missing"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void revealIsAudited() throws Exception {
        // 감사 기록 없이 값 조회가 가능하면 조회 주체 확인 불가.
        var method = CspCredentialServiceImpl.class.getMethod("revealCredential", String.class);

        var audited = method.getAnnotation(com.aipaas.anycloud.domain.audit.Audited.class);
        assertThat(audited).as("revealCredential 에 @Audited 가 없다").isNotNull();
        assertThat(audited.resourceType()).isEqualTo("credential");
    }

    @Test
    void auditDoesNotCarryTheValues() throws Exception {
        // summary 에 값 포함 시 감사 로그가 비밀값 저장소가 됨.
        var audited = CspCredentialServiceImpl.class
                .getMethod("revealCredential", String.class)
                .getAnnotation(com.aipaas.anycloud.domain.audit.Audited.class);

        assertThat(audited.summary()).doesNotContain("#result");
    }
}
