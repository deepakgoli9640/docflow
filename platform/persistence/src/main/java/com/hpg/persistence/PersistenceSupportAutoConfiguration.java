package com.hpg.persistence;

import com.hpg.common.crypto.AesGcmEncryptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.util.Optional;

/**
 * Shared JPA setup for every service that owns a database.
 *
 * <ul>
 *   <li>{@link Clock}: all time comes from one injectable UTC clock, so tests can control it.</li>
 *   <li>JPA auditing fills {@code created_*}/{@code updated_*} columns of {@link AuditableEntity}.</li>
 *   <li>{@link AesGcmEncryptor} exists only when {@code hpg.security.encryption-key} is set.</li>
 * </ul>
 */
@AutoConfiguration(before = HibernateJpaAutoConfiguration.class)
@EnableJpaAuditing(auditorAwareRef = "hpgAuditorAware", dateTimeProviderRef = "hpgAuditDateTimeProvider")
public class PersistenceSupportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public DateTimeProvider hpgAuditDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }

    @Bean
    public AuditorAware<String> hpgAuditorAware() {
        return () -> Optional.of(Actors.current());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("!'${hpg.security.encryption-key:}'.isBlank()")
    public AesGcmEncryptor aesGcmEncryptor(@Value("${hpg.security.encryption-key}") String base64Key) {
        return AesGcmEncryptor.fromBase64Key(base64Key);
    }

    @Bean
    public EncryptedStringConverter encryptedStringConverter(ObjectProvider<AesGcmEncryptor> encryptor) {
        return new EncryptedStringConverter(encryptor);
    }
}
