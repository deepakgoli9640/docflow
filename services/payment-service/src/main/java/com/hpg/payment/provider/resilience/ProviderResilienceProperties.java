package com.hpg.payment.provider.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Prefix {@code hpg.payment.resilience}. Defaults suit local development. */
@ConfigurationProperties(prefix = "hpg.payment.resilience")
public record ProviderResilienceProperties(
        Integer circuitBreakerWindowSize,
        Integer circuitBreakerMinimumCalls,
        Float circuitBreakerFailureRatePercent,
        Duration circuitBreakerOpenDuration,
        Integer liveMaxConcurrentCalls,
        Integer recoveryMaxConcurrentCalls,
        Integer retryMaxAttempts,
        Duration retryInitialInterval,
        Duration retryMaxInterval) {

    public ProviderResilienceProperties {
        circuitBreakerWindowSize = circuitBreakerWindowSize == null ? 20 : circuitBreakerWindowSize;
        circuitBreakerMinimumCalls = circuitBreakerMinimumCalls == null ? 10 : circuitBreakerMinimumCalls;
        circuitBreakerFailureRatePercent = circuitBreakerFailureRatePercent == null ? 50f : circuitBreakerFailureRatePercent;
        circuitBreakerOpenDuration = circuitBreakerOpenDuration == null ? Duration.ofSeconds(30) : circuitBreakerOpenDuration;
        liveMaxConcurrentCalls = liveMaxConcurrentCalls == null ? 20 : liveMaxConcurrentCalls;
        recoveryMaxConcurrentCalls = recoveryMaxConcurrentCalls == null ? 4 : recoveryMaxConcurrentCalls;
        retryMaxAttempts = retryMaxAttempts == null ? 3 : retryMaxAttempts;
        retryInitialInterval = retryInitialInterval == null ? Duration.ofMillis(200) : retryInitialInterval;
        retryMaxInterval = retryMaxInterval == null ? Duration.ofSeconds(2) : retryMaxInterval;
    }
}
