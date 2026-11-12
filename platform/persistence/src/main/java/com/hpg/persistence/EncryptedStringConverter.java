package com.hpg.persistence;

import com.hpg.common.crypto.AesGcmEncryptor;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Transparently encrypts a String column with AES-256-GCM: {@code @Convert(converter = EncryptedStringConverter.class)}.
 *
 * <p>Spring Boot lets Hibernate obtain converters as Spring beans, so the key is injected rather than
 * read from a static. The encryptor is looked up lazily: a service that never uses encrypted columns
 * does not need an encryption key, while one that does fails with a clear message on first use.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final ObjectProvider<AesGcmEncryptor> encryptor;

    public EncryptedStringConverter(ObjectProvider<AesGcmEncryptor> encryptor) {
        this.encryptor = encryptor;
    }

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        return plaintext == null ? null : encryptor().encrypt(plaintext);
    }

    @Override
    public String convertToEntityAttribute(String ciphertext) {
        return ciphertext == null ? null : encryptor().decrypt(ciphertext);
    }

    private AesGcmEncryptor encryptor() {
        AesGcmEncryptor instance = encryptor.getIfAvailable();
        if (instance == null) {
            throw new IllegalStateException("hpg.security.encryption-key (env HPG_ENCRYPTION_KEY) is not set, "
                    + "but an encrypted column is being used. Generate a key with: openssl rand -base64 32");
        }
        return instance;
    }
}
