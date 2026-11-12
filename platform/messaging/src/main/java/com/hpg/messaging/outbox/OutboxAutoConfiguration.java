package com.hpg.messaging.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Registers {@link OutboxWriter}. {@link AutoConfigurationPackage} adds this package to JPA entity and
 * repository scanning, so services do not need to know where the outbox entity lives.
 */
@AutoConfiguration(before = HibernateJpaAutoConfiguration.class)
@AutoConfigurationPackage
public class OutboxAutoConfiguration {

    @Bean
    public OutboxWriter outboxWriter(OutboxEventRepository repository, ObjectMapper objectMapper, Clock clock,
            @Value("${spring.application.name}") String producer) {
        return new OutboxWriter(repository, objectMapper, clock, producer);
    }
}
