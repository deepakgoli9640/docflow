package com.hpg.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.console.client.GatewayApi;
import com.hpg.console.client.TokenClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class ConsoleConfig {

    @Bean
    public RestClient gatewayRestClient(ConsoleProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().baseUrl(properties.gatewayUri()).requestFactory(factory).build();
    }

    @Bean
    public TokenClient tokenClient(RestClient gatewayRestClient) {
        return new TokenClient(gatewayRestClient);
    }

    @Bean
    public GatewayApi gatewayApi(RestClient gatewayRestClient, ObjectMapper objectMapper) {
        return new GatewayApi(gatewayRestClient, objectMapper);
    }
}
