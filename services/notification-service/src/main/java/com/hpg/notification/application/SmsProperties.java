package com.hpg.notification.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * Patient SMS (prefix {@code hpg.notification.sms}).
 *
 * @param enabled   send SMS at all (receipt, failed, expired) when the payment has a phone number
 * @param provider  {@code log} (local: logs a masked number, sends nothing) or {@code msg91}
 * @param msg91     MSG91 Flow API settings. India requires DLT-registered templates: each message type maps to
 *                  a registered template id, and the provider fills its variables.
 */
@ConfigurationProperties("hpg.notification.sms")
public record SmsProperties(@DefaultValue("true") boolean enabled, @DefaultValue("log") String provider, Msg91 msg91) {

    public SmsProperties {
        msg91 = msg91 == null ? new Msg91(null, null, Map.of()) : msg91;
    }

    /**
     * @param baseUrl    {@code https://control.msg91.com}
     * @param authKey    API key (secret)
     * @param templates  public event type ({@code payment.captured}, ...) → DLT template (flow) id
     */
    public record Msg91(@DefaultValue("https://control.msg91.com") String baseUrl, String authKey, Map<String, String> templates) {

        public Msg91 {
            templates = templates == null ? Map.of() : Map.copyOf(templates);
        }
    }
}
