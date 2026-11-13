package com.hpg.console;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param gatewayUri      the console talks to the same edge a hospital's system does, so what you see
 *                        in the browser went through the same routing, authentication and rate limits
 * @param connectTimeout  how long to wait for the gateway to accept a connection
 * @param readTimeout     how long to wait for a response
 * @param liveRefresh     how often a page with in-flight payments refreshes itself
 */
@Validated
@ConfigurationProperties(prefix = "hpg.console")
public record ConsoleProperties(@NotBlank String gatewayUri, Duration connectTimeout, Duration readTimeout,
        Duration liveRefresh) {

    public ConsoleProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        liveRefresh = liveRefresh == null ? Duration.ofSeconds(3) : liveRefresh;
    }
}
