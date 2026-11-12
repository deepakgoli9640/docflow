package com.hpg.merchant.infrastructure.persistence;

import com.hpg.common.crypto.AesGcmEncryptor;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * Transparently encrypts a String column with AES-256-GCM. Spring Boot lets Hibernate obtain
 * converters as Spring beans, so the key is injected rather than read from a static.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final AesGcmEncryptor encryptor;

    public EncryptedStringConverter(AesGcmEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        return encryptor.encrypt(plaintext);
    }

    @Override
    public String convertToEntityAttribute(String ciphertext) {
        return encryptor.decrypt(ciphertext);
    }
}
