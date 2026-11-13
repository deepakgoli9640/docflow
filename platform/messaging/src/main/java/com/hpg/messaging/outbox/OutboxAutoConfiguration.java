package com.hpg.messaging.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
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
            @Value("${spring.application.name}") String producer) {
        return new OutboxWriter(repository, objectMapper, clock, producer);
    }

    /** Pending (unpublished) events: grows when Kafka is unreachable. Alert on it. */
    @Bean
    public MeterBinder outboxMetrics(OutboxEventRepository repository) {
        return registry -> Gauge.builder("outbox.pending.events", repository,
                OutboxEventRepository::countByPublishedAtIsNull).register(registry);
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
