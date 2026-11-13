package com.hpg.reconciliation.infrastructure.config;

import com.hpg.reconciliation.application.ReconProperties;
import com.hpg.reconciliation.domain.BatchStatus;
import com.hpg.reconciliation.domain.ResolutionStatus;
import com.hpg.reconciliation.infrastructure.persistence.BatchRepository;
import com.hpg.reconciliation.infrastructure.persistence.RecordRepository;
import com.hpg.reconciliation.source.SettlementReportSource;
import com.hpg.reconciliation.source.razorpay.RazorpaySettlementSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.Executors;

@Configuration
public class ReconConfig {

    /** Background matching; virtual threads, since runs mostly wait on the database. */
    @Bean(name = "reconExecutor", destroyMethod = "shutdown")
    public java.util.concurrent.ExecutorService reconExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** API-based reconciliation exists only with the real provider; local demos use CSV uploads. */
    @Bean
    @ConditionalOnExpression("'${hpg.reconciliation.provider:razorpay}'.equalsIgnoreCase('razorpay')")
    public SettlementReportSource razorpaySettlementSource(ReconProperties properties) {
        return new RazorpaySettlementSource(properties.razorpay());
    }

    @Bean
    public MeterBinder reconMetrics(RecordRepository records, BatchRepository batches) {
        return registry -> {
            Gauge.builder("recon.open.discrepancies", records,
                    r -> r.countByResolutionStatusIn(List.of(ResolutionStatus.OPEN, ResolutionStatus.IN_REVIEW))).register(registry);
            // Alert if older than 26 h (docs/05-reconciliation.md §7).
            Gauge.builder("recon.last.successful.run.timestamp", batches,
                    b -> b.findFirstByStatusOrderByCompletedAtDesc(BatchStatus.COMPLETED)
                            .map(x -> (double) x.getCompletedAt().getEpochSecond()).orElse(0.0)).register(registry);
        };
    }

}
