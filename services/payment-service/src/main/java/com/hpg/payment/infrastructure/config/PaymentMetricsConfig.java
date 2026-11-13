package com.hpg.payment.infrastructure.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Gauges read from the database at scrape time (docs/10-observability.md §2). */
@Configuration
public class PaymentMetricsConfig {

    /**
     * Payments whose outcome is still being verified after 15 minutes ({@code PaymentsStuck} alert). Recovery
     * normally settles PROCESSING/UNKNOWN within minutes; anything older means the provider or the jobs are not.
     */
    @Bean
    public MeterBinder paymentsStuckGauge(JdbcTemplate jdbc) {
        return registry -> Gauge.builder("payments.stuck", jdbc, j -> {
            Long n = j.queryForObject("SELECT count(*) FROM payments WHERE status IN ('PROCESSING', 'UNKNOWN') "
                    + "AND updated_at < now() - interval '15 minutes'", Long.class);
            return n == null ? 0 : n;
        }).description("Payments in PROCESSING/UNKNOWN for more than 15 minutes").register(registry);
    }
}
