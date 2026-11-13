package com.hpg.notification.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("hpg.notification")
public record NotificationProperties(
        String merchantServiceUri,
        @DefaultValue("notification-service") String clientId,
        String clientSecret,
        @DefaultValue("60s") Duration webhookConfigCacheTtl,
        @DefaultValue("false") boolean allowInsecureWebhooks,
        @DefaultValue("payments@hpg.local") String emailFrom,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration requestTimeout,
        Dispatch dispatch) {

    /**
     * @param lease        how long a claimed notification is hidden from other dispatchers while it is delivered
     * @param maxAge       give up (DEAD) once the first attempt is older than this
     * @param maxAttempts  ... or after this many attempts
     */
    public record Dispatch(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1s") Duration interval,
            @DefaultValue("50") int batchSize,
            @DefaultValue("2m") Duration lease,
            @DefaultValue("10s") Duration initialBackoff,
            @DefaultValue("1h") Duration maxBackoff,
            @DefaultValue("24h") Duration maxAge,
            @DefaultValue("20") int maxAttempts) {
    }

    @Override
    public String toString() {
        return "NotificationProperties[merchantServiceUri=" + merchantServiceUri + ", clientId=" + clientId
                + ", clientSecret=****]";
    }
}
