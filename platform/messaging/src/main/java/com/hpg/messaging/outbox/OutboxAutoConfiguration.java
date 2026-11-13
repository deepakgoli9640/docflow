package com.hpg.messaging.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;

/**
 * Wires the transactional outbox. {@link AutoConfigurationPackage} adds this package to JPA entity and
 * repository scanning, so a service does not have to know where the outbox entity lives.
 *
 * <p>The relay publishes on a schedule; tests set {@code hpg.outbox.enabled=false} and call
 * {@link OutboxRelay#publishBatch()} directly, so they never depend on timing.
 */
@AutoConfiguration(before = HibernateJpaAutoConfiguration.class)
@AutoConfigurationPackage
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    public OutboxWriter outboxWriter(OutboxEventRepository repository, ObjectMapper objectMapper, Clock clock,
            @Value("${spring.application.name}") String producer) {
        return new OutboxWriter(repository, objectMapper, clock, producer);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(KafkaTemplate.class)
    public OutboxRelay outboxRelay(OutboxEventRepository repository, KafkaTemplate<String, byte[]> kafkaTemplate,
            OutboxProperties properties, MeterRegistry meters, Clock clock) {
        return new OutboxRelay(repository, kafkaTemplate, properties, meters, clock);
    }

    @Bean
    @ConditionalOnBean(OutboxRelay.class)
    @ConditionalOnProperty(name = "hpg.outbox.enabled", havingValue = "true", matchIfMissing = true)
    public OutboxRelayJob outboxRelayJob(OutboxRelay relay) {
        return new OutboxRelayJob(relay);
    }

    @Bean
    public OutboxMetrics outboxMetrics(OutboxEventRepository repository, MeterRegistry meters, Clock clock) {
        return new OutboxMetrics(repository, meters, clock);
    }
}
