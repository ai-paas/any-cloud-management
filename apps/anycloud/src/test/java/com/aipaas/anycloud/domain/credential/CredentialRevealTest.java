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
 * 등록된 자격증명 값 보기.
 *
 * <p>값은 목록에 섞지 않는다 — 캐시와 로그에 남는다. 전용 경로로만 꺼내고 누가 언제 무엇을
 * 봤는지 감사 로그에 남긴다.
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
        // 감사 기록 없이 값을 꺼낼 수 있으면 누가 봤는지 알 수 없다.
        var method = CspCredentialServiceImpl.class.getMethod("revealCredential", String.class);

        var audited = method.getAnnotation(com.aipaas.anycloud.domain.audit.Audited.class);
        assertThat(audited).as("revealCredential 에 @Audited 가 없다").isNotNull();
        assertThat(audited.resourceType()).isEqualTo("credential");
    }

    @Test
    void auditDoesNotCarryTheValues() throws Exception {
        // summary 에 값을 넣으면 감사 로그가 비밀 저장소가 된다.
        var audited = CspCredentialServiceImpl.class
                .getMethod("revealCredential", String.class)
                .getAnnotation(com.aipaas.anycloud.domain.audit.Audited.class);

        assertThat(audited.summary()).doesNotContain("#result");
    }
}
