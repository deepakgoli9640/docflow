package com.hpg.merchant.infrastructure.config;

import com.hpg.common.crypto.AesGcmEncryptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EncryptionConfig {

    /**
     * Fails application startup if the key is missing or malformed. Starting without it would either
     * crash later on the first write or, worse, tempt someone to add a hardcoded fallback key.
     */
    @Bean
    public AesGcmEncryptor aesGcmEncryptor(@Value("${hpg.security.encryption-key:}") String base64Key) {
        if (base64Key.isBlank()) {
            throw new IllegalStateException("hpg.security.encryption-key (env HPG_ENCRYPTION_KEY) is not set. "
                    + "Generate one with: openssl rand -base64 32");
        }
        return AesGcmEncryptor.fromBase64Key(base64Key);
    }
}
