package com.hpg.notification.delivery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignerTest {

    @Test
    void signsTimestampDotBody() {
        // Reference value: printf '1794474904.{"a":1}' | openssl dgst -sha256 -hmac secret
        assertThat(WebhookSigner.sign("secret", 1794474904L, "{\"a\":1}"))
                .isEqualTo("v1=" + javaHmac("secret", "1794474904.{\"a\":1}"));
    }

    @Test
    void timestampIsPartOfTheSignature() {
        assertThat(WebhookSigner.sign("s", 1, "{}")).isNotEqualTo(WebhookSigner.sign("s", 2, "{}"));
        assertThat(WebhookSigner.sign("s", 1, "{}")).isNotEqualTo(WebhookSigner.sign("t", 1, "{}"));
    }

    private static String javaHmac(String key, String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(data.getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
