package com.hpg.console;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The web console: hospital staff and operations, in one role-aware application.
 *
 * <p>It is a <b>backend-for-frontend</b>, not a microservice. It owns no database and no business
 * rules: every page is assembled from the same public APIs a hospital's own system would call, through
 * the gateway. If a rule looks like it lives here, it is a bug — it belongs in the service that owns
 * the data.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ConsoleServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsoleServiceApplication.class, args);
    }
}
