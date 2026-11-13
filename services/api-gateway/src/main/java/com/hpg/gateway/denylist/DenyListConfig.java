package com.hpg.gateway.denylist;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Duration;

@Configuration
public class DenyListConfig {

    @Bean
    public MerchantDenyList merchantDenyList(ReactiveStringRedisTemplate redis,
            @Value("${hpg.gateway.deny-list.ttl:20m}") Duration ttl) {
        return new MerchantDenyList(redis, ttl);
    }

    @Bean
    public MerchantDenyListFilter merchantDenyListFilter(MerchantDenyList denyList, ObjectMapper json,
            MeterRegistry meters) {
        return new MerchantDenyListFilter(denyList, json, meters);
    }

    @Bean
    public MerchantSnapshotListener merchantSnapshotListener(MerchantDenyList denyList, ObjectMapper json) {
        return new MerchantSnapshotListener(denyList, json);
    }
}
