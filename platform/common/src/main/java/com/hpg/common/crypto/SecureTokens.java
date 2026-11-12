package com.hpg.common.crypto;

import java.security.SecureRandom;
import java.util.Base64;

/** Cryptographically random, URL-safe tokens (API key secrets, webhook secrets, checkout tokens). */
public final class SecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHANUMERIC =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private SecureTokens() {
    }

    /** Base64url (no padding) encoding of {@code bytes} random bytes. 32 bytes = 256 bits of entropy. */
    public static String urlSafe(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    /** Random alphanumeric string, used for public identifiers such as API key ids. */
    public static String alphanumeric(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUMERIC[RANDOM.nextInt(ALPHANUMERIC.length)]);
        }
        return sb.toString();
    }
}
