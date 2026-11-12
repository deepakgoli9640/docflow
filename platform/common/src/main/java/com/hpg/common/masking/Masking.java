package com.hpg.common.masking;

/**
 * Masks personal and secret data before it is shown in responses or logs.
 *
 * <p>Masking is a safety net, not the primary control. The primary control is to never log objects
 * that contain sensitive fields in the first place.
 */
public final class Masking {

    private static final String MASK = "****";

    private Masking() {
    }

    /** {@code patient@example.com -> p*****t@example.com}. */
    public static String email(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return MASK;
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) {
            return local.charAt(0) + "*" + domain;
        }
        return local.charAt(0) + "*".repeat(local.length() - 2) + local.charAt(local.length() - 1) + domain;
    }

    /** {@code +919800001234 -> +91******1234}: keeps a country prefix and the last four digits. */
    public static String phone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        if (phone.length() <= 4) {
            return MASK;
        }
        int keepPrefix = phone.startsWith("+") ? Math.min(3, phone.length() - 4) : 0;
        String prefix = phone.substring(0, keepPrefix);
        String last4 = phone.substring(phone.length() - 4);
        return prefix + "*".repeat(phone.length() - keepPrefix - 4) + last4;
    }

    /** Shows only the first 4 characters of a secret/identifier: {@code hpgk_****}. */
    public static String secret(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.length() <= 4 ? MASK : value.substring(0, 4) + MASK;
    }
}
