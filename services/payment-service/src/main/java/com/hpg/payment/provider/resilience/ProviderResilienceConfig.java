package com.hpg.payment.provider.resilience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.payment.provider.PaymentProvider;
import com.hpg.payment.provider.ProviderRejectedException;
import com.hpg.payment.provider.ProviderTimeoutException;
import com.hpg.payment.provider.ProviderUnavailableException;
import com.hpg.payment.provider.razorpay.RazorpayPaymentProvider;
import com.hpg.payment.provider.razorpay.RazorpayProperties;
import com.hpg.payment.provider.simulated.SimulatedPaymentProvider;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Chooses the provider adapter and wraps it with Resilience4j. Configured in code rather than YAML so
 * the policies are visible and reviewable in one place.
 */
@Configuration
@EnableConfigurationProperties({RazorpayProperties.class, ProviderResilienceProperties.class})
public class ProviderResilienceConfig {

    @Bean
    @ConditionalOnProperty(name = "hpg.payment.provider", havingValue = "razorpay", matchIfMissing = true)
    public PaymentProvider razorpayPaymentProvider(RazorpayProperties properties, ObjectMapper objectMapper) {
        return new RazorpayPaymentProvider(properties, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "hpg.payment.provider", havingValue = "simulated")
    public SimulatedPaymentProvider simulatedPaymentProvider() {
        return new SimulatedPaymentProvider();
    }

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(ProviderResilienceProperties p, MeterRegistry meters) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(p.circuitBreakerWindowSize())
                .minimumNumberOfCalls(p.circuitBreakerMinimumCalls())
                .failureRateThreshold(p.circuitBreakerFailureRatePercent())
                .slowCallDurationThreshold(Duration.ofSeconds(3))
                .slowCallRateThreshold(80)
                .waitDurationInOpenState(p.circuitBreakerOpenDuration())
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(ProviderUnavailableException.class, ProviderTimeoutException.class)
                .ignoreExceptions(ProviderRejectedException.class)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        return registry;
    }

    @Bean
    public PaymentProviders paymentProviders(PaymentProvider provider, CircuitBreakerRegistry circuitBreakers,
            ProviderResilienceProperties p, MeterRegistry meters) {
        CircuitBreaker breaker = circuitBreakers.circuitBreaker("payment-provider");

        BulkheadRegistry bulkheads = BulkheadRegistry.ofDefaults();
        Bulkhead liveBulkhead = bulkheads.bulkhead("provider-live", BulkheadConfig.custom()
                .maxConcurrentCalls(p.liveMaxConcurrentCalls()).maxWaitDuration(Duration.ofMillis(100)).build());
        Bulkhead recoveryBulkhead = bulkheads.bulkhead("provider-recovery", BulkheadConfig.custom()
                .maxConcurrentCalls(p.recoveryMaxConcurrentCalls()).maxWaitDuration(Duration.ZERO).build());
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheads).bindTo(meters);

        // Exponential backoff with jitter: 200 ms, ~400 ms, ~800 ms ... capped, each randomised by +/-50%
        // so that many clients retrying after the same outage do not hit the provider in lock-step.
        IntervalFunction backoff = IntervalFunction.ofExponentialRandomBackoff(
                p.retryInitialInterval(), 2.0, 0.5, p.retryMaxInterval());
        RetryRegistry retries = RetryRegistry.ofDefaults();
        Retry safe = retries.retry("provider-safe", RetryConfig.custom()
                .maxAttempts(p.retryMaxAttempts()).intervalFunction(backoff)
                .retryExceptions(ProviderUnavailableException.class)
                .build());
        Retry idempotent = retries.retry("provider-idempotent", RetryConfig.custom()
                .maxAttempts(p.retryMaxAttempts()).intervalFunction(backoff)
                .retryExceptions(ProviderUnavailableException.class, ProviderTimeoutException.class)
                .build());
        TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(meters);

        return new PaymentProviders(
                new ResilientPaymentProvider(provider, breaker, liveBulkhead, safe, idempotent, meters, "live"),
                new ResilientPaymentProvider(provider, breaker, recoveryBulkhead, safe, idempotent, meters, "recovery"));
    }
}
