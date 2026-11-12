package com.hpg.merchant.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One real PostgreSQL per test JVM (Spring caches the context, and with it this container).
 *
 * <p>Real Postgres rather than H2 because the things worth testing here — JSONB, partial indexes,
 * CHECK constraints, triggers, row locks — behave differently or do not exist in H2.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestContainer {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("merchant_db");
    }
}
