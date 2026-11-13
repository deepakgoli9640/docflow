package com.hpg.transaction.infrastructure.config;

import com.hpg.transaction.application.LedgerQueryService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LedgerMetricsConfig {

    /** Number of failing integrity conditions; anything above 0 raises {@code LedgerIntegrityViolation}. */
    @Bean
    public MeterBinder ledgerIntegrityGauge(LedgerQueryService ledger) {
        return registry -> Gauge.builder("ledger.integrity.violations", ledger, l -> {
            var r = l.integrity();
            return r.unbalancedPostings() + r.settledTransactionsWithoutPosting() + r.negativeMerchantPayables()
                    + (r.debitsEqualCredits() ? 0 : 1);
        }).description("Failing ledger integrity checks (0 = healthy)").register(registry);
    }
}
