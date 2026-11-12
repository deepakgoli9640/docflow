package com.hpg.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Authenticated field-level encryption (AES-256-GCM) for data that must be stored but also read back,
 * such as hospital webhook signing secrets and patient contact details.
 *
 * <p>Output format: {@code v1:<base64(iv || ciphertext+tag)>}. A fresh random 96-bit IV is used for
 * every value — reusing an IV with the same key breaks GCM completely. The version prefix allows a
 * future key rotation to recognise which key/format produced a value.
 *
 * <p>Secrets that never need to be read back (merchant API key secrets) are hashed with bcrypt instead.
 * In production the key would come from a KMS (envelope encryption); here it comes from an environment
 * variable.
 */
public final class AesGcmEncryptor {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public AesGcmEncryptor(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalArgumentException("AES-256 key must be exactly 32 bytes");
        }
        this.key = new SecretKeySpec(keyBytes.clone(), "AES");
    }

    /** Creates an encryptor from a base64-encoded 32-byte key. */
    public static AesGcmEncryptor fromBase64Key(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("Encryption key is not configured");
        }
        return new AesGcmEncryptor(Base64.getDecoder().decode(base64Key.trim()));
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer out = ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext);
            return PREFIX + Base64.getEncoder().encodeToString(out.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null) {
            return null;
        }
        if (!encoded.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Unsupported ciphertext format");
        }
        try {
            byte[] data = Base64.getDecoder().decode(encoded.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Tampered ciphertext or wrong key. Never return partial data.
            throw new IllegalStateException("Decryption failed (wrong key or tampered value)", e);
        }
    }
}
