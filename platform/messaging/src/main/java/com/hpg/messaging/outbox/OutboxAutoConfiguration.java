package com.hpg.messaging.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Registers {@link OutboxWriter} and, when Kafka is configured, the {@link OutboxRelay}.
 * {@link AutoConfigurationPackage} adds this package to JPA entity and repository scanning, so services do
 * not need to know where the outbox entity lives.
 */
@AutoConfiguration(before = HibernateJpaAutoConfiguration.class, after = KafkaAutoConfiguration.class)
@AutoConfigurationPackage
@EnableConfigurationProperties(OutboxRelayProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    public OutboxWriter outboxWriter(OutboxEventRepository repository, ObjectMapper objectMapper, Clock clock,
            @Value("${spring.application.name}") String producer, ObjectProvider<Tracer> tracer) {
        return new OutboxWriter(repository, objectMapper, clock, producer, () -> {
            Tracer t = tracer.getIfAvailable();
            Span span = t == null ? null : t.currentSpan();
            if (span == null) {
                return null;
            }
            TraceContext c = span.context();
            return "00-" + c.traceId() + "-" + c.spanId() + "-" + (Boolean.TRUE.equals(c.sampled()) ? "01" : "00");
        });
    }

    /**
     * Pending (unpublished) events and the age of the oldest one: both grow when Kafka is unreachable
     * ({@code OutboxBacklog} alert at 2 minutes).
     */
    @Bean
    public MeterBinder outboxMetrics(OutboxEventRepository repository, JdbcTemplate jdbc) {
        return registry -> {
            Gauge.builder("outbox.pending.events", repository, OutboxEventRepository::countByPublishedAtIsNull)
                    .register(registry);
            Gauge.builder("outbox.oldest.pending.age.seconds", jdbc, j -> {
                Double age = j.queryForObject("SELECT COALESCE(EXTRACT(EPOCH FROM (now() - min(created_at))), 0) "
                        + "FROM outbox_events WHERE published_at IS NULL", Double.class);
                return age == null ? 0 : age;
            }).register(registry);
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnProperty(name = "hpg.messaging.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
    static class RelayConfiguration {

        @Bean
        OutboxRelay outboxRelay(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                KafkaTemplate<String, String> kafka, OutboxRelayProperties properties, Clock clock) {
            return new OutboxRelay(jdbc, new TransactionTemplate(transactionManager), kafka, properties, clock);
        }
    }
}
