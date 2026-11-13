package com.hpg.gateway.config;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Resolve downstream hosts with the JVM resolver instead of Reactor Netty's own DNS client.
 *
 * <p>Netty's resolver caches answers for the record's TTL (Docker's embedded DNS: 600 s). When a service
 * container is recreated it gets a new IP, and the gateway kept connecting to the old one (503 for minutes).
 * The JVM resolver caches successful lookups for 30 s ({@code networkaddress.cache.ttl}), so a restarted
 * service is reachable again almost immediately. Same effect for Kubernetes pods behind headless services.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClientCustomizer jvmDnsResolver() {
        return client -> client.resolver(DefaultAddressResolverGroup.INSTANCE);
    }
}
