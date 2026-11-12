package com.hpg.common.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmEncryptorTest {

    private static String key() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    @Test
    void roundTrips() {
        AesGcmEncryptor encryptor = AesGcmEncryptor.fromBase64Key(key());
        String encrypted = encryptor.encrypt("whsec_super_secret");
        assertThat(encrypted).startsWith("v1:").doesNotContain("whsec_super_secret");
        assertThat(encryptor.decrypt(encrypted)).isEqualTo("whsec_super_secret");
    }

    @Test
    void usesFreshIvSoSamePlaintextGivesDifferentCiphertext() {
        AesGcmEncryptor encryptor = AesGcmEncryptor.fromBase64Key(key());
        assertThat(encryptor.encrypt("same")).isNotEqualTo(encryptor.encrypt("same"));
    }

    @Test
    void detectsTampering() {
        AesGcmEncryptor encryptor = AesGcmEncryptor.fromBase64Key(key());
        String encrypted = encryptor.encrypt("value");
        char last = encrypted.charAt(encrypted.length() - 2);
        String tampered = encrypted.substring(0, encrypted.length() - 2) + (last == 'A' ? 'B' : 'A')
                + encrypted.charAt(encrypted.length() - 1);
        assertThatThrownBy(() -> encryptor.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongKeyCannotDecrypt() {
        String encrypted = AesGcmEncryptor.fromBase64Key(key()).encrypt("value");
        byte[] other = new byte[32];
        other[0] = 1;
        assertThatThrownBy(() -> new AesGcmEncryptor(other).decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsWrongKeyLength() {
        assertThatThrownBy(() -> new AesGcmEncryptor(new byte[16])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AesGcmEncryptor.fromBase64Key(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullPassesThrough() {
        AesGcmEncryptor encryptor = AesGcmEncryptor.fromBase64Key(key());
        assertThat(encryptor.encrypt(null)).isNull();
        assertThat(encryptor.decrypt(null)).isNull();
    }
}
