package com.hpg.persistence.scheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * Scheduled jobs with ShedLock, for every service that owns a database.
 *
 * <p>With more than one replica, each replica's scheduler fires. ShedLock turns that into "one replica
 * actually runs the job": the winner holds a row in the {@code shedlock} table for the duration. Lock
 * expiry uses <em>database</em> time ({@code usingDbTime}), so replicas with skewed clocks still agree.
 *
 * <p>Each service creates the {@code shedlock} table in its own Flyway migrations.
 * Set {@code hpg.scheduling.enabled=false} (tests do) to keep schedulers out of the way.
 */
// The "after" matters: a condition on the DataSource bean is evaluated when this class is processed, so
// without it this auto-configuration can be considered before the DataSource exists and silently skip,
// taking every scheduled job in the service with it.
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
@ConditionalOnClass(JdbcTemplateLockProvider.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(name = "hpg.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingSupportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LockProvider shedLockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
