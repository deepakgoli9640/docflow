package com.hpg.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.web.correlation.CorrelationIdFilter;
import com.hpg.web.error.GlobalExceptionHandler;
import com.hpg.web.security.HpgJwtAuthenticationConverter;
import com.hpg.web.security.MerchantPrincipalArgumentResolver;
import com.hpg.web.security.ProblemDetailsSecurityHandlers;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Registers the shared web plumbing in every servlet service that depends on this module.
 *
 * <p>Runs before {@link WebMvcAutoConfiguration} so that Spring Boot's own Problem Details handler
 * (registered only when no {@code ResponseEntityExceptionHandler} exists) sees ours and backs off.
 * Otherwise both would be active and the order between them would decide which format a client gets.
 */
@AutoConfiguration(before = WebMvcAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@org.springframework.boot.context.properties.EnableConfigurationProperties(com.hpg.web.security.ExternalIssuerProperties.class)
public class WebSupportAutoConfiguration {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        // Before Spring Security, so even 401/403 responses and their log lines carry the correlation ID.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * Load shedding (on by default): {@code hpg.web.max-concurrent-requests} at a time per instance, a short wait,
     * then a fast 503. Size it a few times the database pool: more in flight only queues for connections.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "hpg.web.load-shedding.enabled",
            havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<com.hpg.web.resilience.LoadSheddingFilter> loadSheddingFilter(ObjectMapper mapper,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters,
            @org.springframework.beans.factory.annotation.Value("${hpg.web.load-shedding.max-concurrent:40}") int maxConcurrent,
            @org.springframework.beans.factory.annotation.Value("${hpg.web.load-shedding.max-wait:250ms}") java.time.Duration maxWait) {
        FilterRegistrationBean<com.hpg.web.resilience.LoadSheddingFilter> registration = new FilterRegistrationBean<>(
                new com.hpg.web.resilience.LoadSheddingFilter(maxConcurrent, maxWait.toMillis(), mapper, meters.getIfAvailable()));
        // After the correlation id (so a refusal can be traced), before security (a refused request costs nothing).
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public HpgJwtAuthenticationConverter hpgJwtAuthenticationConverter() {
        return new HpgJwtAuthenticationConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    public ProblemDetailsSecurityHandlers problemDetailsSecurityHandlers(ObjectMapper objectMapper) {
        return new ProblemDetailsSecurityHandlers(objectMapper);
    }

    @Bean
    public WebMvcConfigurer merchantPrincipalResolverConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new MerchantPrincipalArgumentResolver());
            }
        };
    }
}
