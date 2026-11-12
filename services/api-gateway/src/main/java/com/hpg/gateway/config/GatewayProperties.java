package com.hpg.gateway.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param jwkSetUri where merchant-service publishes its token signing public keys
 * @param issuer    expected {@code iss} claim; tokens from any other issuer are rejected
 */
@Validated
@ConfigurationProperties(prefix = "hpg.gateway.jwt")
public record GatewayProperties(@NotBlank String jwkSetUri, @NotBlank String issuer) {
}
