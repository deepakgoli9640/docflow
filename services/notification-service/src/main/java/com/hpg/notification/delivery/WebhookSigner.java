package com.hpg.notification.delivery;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Outgoing webhook signature (docs/08-api-contracts.md §8):
 * {@code X-HPG-Signature: v1=hex(HMAC-SHA256(secret, "<timestamp>.<raw body>"))}. Including the timestamp
 * lets hospitals reject replays older than 5 minutes.
 */
public final class WebhookSigner {

    public static final String SIGNATURE = "X-HPG-Signature";
    public static final String TIMESTAMP = "X-HPG-Timestamp";
    public static final String EVENT_ID = "X-HPG-Event-Id";
    public static final String EVENT_TYPE = "X-HPG-Event-Type";

    private WebhookSigner() {
    }

    public static String sign(String secret, long timestampEpochSeconds, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestampEpochSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
            return "v1=" + HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
