package com.aipaas.anycloud.domain.credential;

public interface CspCredentialCryptoService {

    String encrypt(String plainText);

    String decrypt(String encryptedText);
}
