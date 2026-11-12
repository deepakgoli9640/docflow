package com.hpg.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.error.CommonErrorCode;
import com.hpg.common.security.Roles;
import com.hpg.common.security.TokenClaims;
import com.hpg.gateway.error.GatewayProblems;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;

/**
 * Edge authentication. The gateway rejects requests without a valid token early (cheap, protects the
 * services from junk traffic), but the services validate the token again: they never trust that a
 * request came through the gateway.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ObjectMapper mapper) {
        ServerAuthenticationEntryPoint entryPoint = (exchange, ex) -> {
            exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
            return GatewayProblems.write(exchange, mapper, CommonErrorCode.UNAUTHENTICATED,
                    "A valid bearer token is required.");
        };
        ServerAccessDeniedHandler deniedHandler = (exchange, ex) -> GatewayProblems.write(exchange, mapper,
                CommonErrorCode.FORBIDDEN, "You are not allowed to perform this operation.");

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)       // bearer tokens only, no cookies
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .authorizeExchange(auth -> auth
                        // Token endpoint authenticates the client itself (HTTP Basic, checked by merchant-service).
                        .pathMatchers("/oauth2/token", "/oauth2/jwks").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/merchants").permitAll()
                        // Actuator lives on the separate management port (9080), which is not published.
                        .pathMatchers("/actuator/**").permitAll()
                        // Service-internal endpoints are never exposed through the edge.
                        .pathMatchers("/internal/**").denyAll()
                        .pathMatchers("/api/v1/admin/**").hasRole(Roles.OPS)
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .build();
    }

    /**
     * Validates signature (keys fetched from merchant-service's JWKS and cached; refetched when an unknown
     * {@code kid} appears, which is how key rotation works), expiry and issuer.
     */
    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder(GatewayProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    private static ReactiveJwtAuthenticationConverterAdapter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenClaims.ROLES);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
