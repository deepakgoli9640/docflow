package com.hpg.refund.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.refund.application.RefundProperties;
import com.hpg.refund.provider.RefundProvider;
import com.hpg.refund.provider.razorpay.RazorpayRefundProvider;
import com.hpg.refund.provider.simulated.SimulatedRefundProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/** Chooses the refund provider by {@code hpg.refund.provider} (same switch as payment-service). */
@Configuration
public class ProviderConfig {

    @Bean
    public RefundProvider refundProvider(RefundProperties properties, ObjectMapper json) {
        return switch (properties.provider().toLowerCase(Locale.ROOT)) {
            case "razorpay" -> new RazorpayRefundProvider(properties.razorpay(), json);
            case "simulated" -> new SimulatedRefundProvider();
            default -> throw new IllegalStateException("Unknown hpg.refund.provider: " + properties.provider());
        };
    }
}
