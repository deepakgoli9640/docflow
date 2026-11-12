package com.hpg.merchant.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.util.Optional;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware", dateTimeProviderRef = "auditDateTimeProvider")
public class PersistenceConfig {

    /** All time in the service comes from this clock so tests can control it. Always UTC. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public DateTimeProvider auditDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }

    /** Who changed a row: the token subject (API key id or ops client), or "anonymous" for public calls. */
    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.of(currentActor());
    }

    public static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return "anonymous";
        }
        return auth.getName();
    }
}
