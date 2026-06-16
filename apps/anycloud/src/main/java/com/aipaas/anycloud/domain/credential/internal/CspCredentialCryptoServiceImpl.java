package com.aipaas.anycloud.domain.credential.internal;

import com.aipaas.anycloud.common.error.enums.ErrorCode;
import com.aipaas.anycloud.common.error.exception.CustomException;
import com.aipaas.anycloud.domain.credential.CspCredentialCryptoService;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CspCredentialCryptoServiceImpl implements CspCredentialCryptoService {

    private static final String AES = "AES";
    private static final String AES_GCM_NO_PADDING = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final int MIN_KEY_LENGTH = 32;
    /** 공통 sentinel 값. 누군가 default 로 두고 ENV 만 .env.sample 에서 복사한 경우 차단. */
    private static final Set<String> FORBIDDEN_SENTINELS = Set.of(
            "change-me",
            "changeme",
            "your-key-here",
            "your-secret-here",
            "anycloud-secret",
            "anycloud-key",
            "secret",
            "password");

    private final SecureRandom secureRandom = new SecureRandom();
    private final String encryptionKey;

    public CspCredentialCryptoServiceImpl(@Value("${csp-credential.encryption-key:}") String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    /**
     * 부팅 시 한 번 검증. key 가 비어 있어도 ENV 설정한 운영자에게 명확히 fail-fast 시그널을 준다.
     * <ul>
     *   <li>blank → warn (MANUAL credential 없이 ENV-only 운영도 가능하므로 abort 는 안 함)</li>
     *   <li>{@value #MIN_KEY_LENGTH} 미만 → fail-fast (약한 key 로 운영 진입 차단)</li>
     *   <li>sentinel default ("change-me" 등) → fail-fast</li>
     * </ul>
     */
    @PostConstruct
    void validateEncryptionKey() {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            log.warn("*** csp-credential.encryption-key NOT SET *** MANUAL credential 저장/조회는 "
                    + "런타임에 실패합니다. ENV-only credential 만 사용하는 환경이면 무시. "
                    + "MANUAL credential 사용 시 CSP_CREDENTIAL_ENCRYPTION_KEY 설정 (min "
                    + MIN_KEY_LENGTH + " chars) 필요.");
            return;
        }
        if (encryptionKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException("CSP_CREDENTIAL_ENCRYPTION_KEY too short: "
                    + encryptionKey.length() + " chars (min " + MIN_KEY_LENGTH
                    + "). Generate with: openssl rand -hex 32");
        }
        if (FORBIDDEN_SENTINELS.contains(encryptionKey.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalStateException("CSP_CREDENTIAL_ENCRYPTION_KEY is a known weak sentinel "
                    + "value. Use a cryptographically random secret (openssl rand -hex 32).");
        }
        log.info("CSP credential encryption key validated ({} chars)", encryptionKey.length());
    }

    @Override
    public String encrypt(String plainText) {
        assertEncryptionKeyConfigured();
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM_NO_PADDING);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(cipherText);
        } catch (Exception e) {
            throw new CustomException("Failed to encrypt CSP credential payload", ErrorCode.RUNTIME_EXCEPTION);
        }
    }

    @Override
    public String decrypt(String encryptedText) {
        assertEncryptionKeyConfigured();
        try {
            String[] parts = encryptedText.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("encryptedText is not in 'iv:cipher' format — likely corrupted row");
            }
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] cipherText = Base64.getDecoder().decode(parts[1]);

            Cipher cipher = Cipher.getInstance(AES_GCM_NO_PADDING);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (javax.crypto.AEADBadTagException e) {
            // AES-GCM 태그 검증 실패 — 거의 확정적으로 AES 키 불일치.
            // 운영 중 CSP_CREDENTIAL_ENCRYPTION_KEY 가 바뀌었거나 .env 가 reload 된 케이스.
            log.error(
                    "CSP credential decrypt failed: AES-GCM tag verification failed — "
                            + "encryption key likely changed since this credential was stored. "
                            + "Either restore the previous CSP_CREDENTIAL_ENCRYPTION_KEY value or "
                            + "delete + re-register the credential. cause={}",
                    e.toString());
            throw new CustomException(
                    "Failed to decrypt CSP credential payload (AES tag mismatch — encryption key may have changed; "
                            + "re-register the credential)",
                    ErrorCode.RUNTIME_EXCEPTION);
        } catch (Exception e) {
            log.error("CSP credential decrypt failed: {}", e.toString(), e);
            throw new CustomException(
                    "Failed to decrypt CSP credential payload (" + e.getClass().getSimpleName() + ")",
                    ErrorCode.RUNTIME_EXCEPTION);
        }
    }

    private SecretKeySpec secretKey() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = digest.digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(keyBytes, AES);
    }

    private void assertEncryptionKeyConfigured() {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new CustomException(
                    "CSP_CREDENTIAL_ENCRYPTION_KEY is required for manual credential storage",
                    ErrorCode.INVALID_INPUT_VALUE);
        }
    }
}
