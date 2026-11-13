package com.hpg.notification.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.notification.application.NotificationProperties;
import com.hpg.notification.application.RetryPolicy;
import com.hpg.notification.delivery.EmailDeliveryChannel;
import com.hpg.notification.delivery.LoggingSmsSender;
import com.hpg.notification.delivery.PrivateNetworkGuard;
import com.hpg.notification.delivery.SmsDeliveryChannel;
import com.hpg.notification.delivery.SmsSender;
import com.hpg.notification.delivery.WebhookDeliveryChannel;
import com.hpg.notification.infrastructure.client.MerchantServiceClient;
import com.hpg.notification.infrastructure.client.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/** Wires the delivery channels and the one internal client this service needs. */
@Configuration
public class DeliveryConfig {

    @Bean
    public RetryPolicy retryPolicy(NotificationProperties properties) {
        return new RetryPolicy(properties.maxAttempts(), properties.retryInitial(), properties.retryMultiplier(),
                properties.retryMax());
    }

    @Bean
    public PrivateNetworkGuard privateNetworkGuard(NotificationProperties properties) {
        return new PrivateNetworkGuard(properties.allowPrivateWebhookTargets());
    }

    @Bean
    public ServiceTokenProvider serviceTokenProvider(
            @Value("${hpg.notification.merchant-service-uri}") String merchantServiceUri,
            @Value("${hpg.notification.client-id:notification-service}") String clientId,
            @Value("${hpg.notification.client-secret:}") String clientSecret, Clock clock) {
        if (clientSecret.isBlank()) {
            throw new IllegalStateException("hpg.notification.client-secret (env HPG_NOTIFICATION_CLIENT_SECRET) "
                    + "is not set; notification-service cannot authenticate to merchant-service.");
        }
        RestClient http = RestClient.builder().baseUrl(merchantServiceUri)
                .requestFactory(requestFactory(Duration.ofSeconds(2), Duration.ofSeconds(5)))
                .build();
        return new ServiceTokenProvider(http, clientId, clientSecret, clock);
    }

    @Bean
    public MerchantServiceClient merchantServiceClient(
            @Value("${hpg.notification.merchant-service-uri}") String merchantServiceUri,
            ServiceTokenProvider tokens, NotificationProperties properties, MeterRegistry meters) {
        RestClient http = RestClient.builder().baseUrl(merchantServiceUri)
                .requestFactory(requestFactory(Duration.ofSeconds(2), Duration.ofSeconds(5)))
                .build();
        // Half-open after 20s: long enough not to hammer a restarting merchant-service, short enough that
        // a backlog of notifications starts draining soon after it returns.
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(20))
                .permittedNumberOfCallsInHalfOpenState(2)
                .ignoreExceptions(MerchantServiceClient.MerchantNotFoundException.class)
                .build());
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        CircuitBreaker breaker = registry.circuitBreaker("merchant-service");
        return new MerchantServiceClient(http, tokens, breaker, properties.merchantConfigCacheTtl(), 10_000);
    }

    @Bean
    public WebhookDeliveryChannel webhookDeliveryChannel(MerchantServiceClient merchants, PrivateNetworkGuard guard,
            ObjectMapper objectMapper, NotificationProperties properties, Clock clock) {
        return new WebhookDeliveryChannel(merchants, guard, objectMapper, properties.webhookConnectTimeout(),
                properties.webhookReadTimeout(), clock);
    }

    @Bean
    public EmailDeliveryChannel emailDeliveryChannel(JavaMailSender mailSender, NotificationProperties properties) {
        return new EmailDeliveryChannel(mailSender, properties.fromAddress());
    }

    /**
     * No SMS provider is integrated. This logs instead of sending, and says so on every line and at
     * startup, so a demo can never be mistaken for a working integration.
     */
    @Bean
    @ConditionalOnMissingBean(SmsSender.class)
    public SmsSender smsSender() {
        return new LoggingSmsSender();
    }

    @Bean
    public SmsDeliveryChannel smsDeliveryChannel(SmsSender smsSender) {
        return new SmsDeliveryChannel(smsSender);
    }

    private static JdkClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
