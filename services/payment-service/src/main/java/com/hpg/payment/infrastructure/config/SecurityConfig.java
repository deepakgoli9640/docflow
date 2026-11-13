package com.hpg.payment.infrastructure.config;

import com.hpg.common.security.Roles;
import com.hpg.web.security.ExternalIssuerProperties;
import com.hpg.web.security.HpgJwtAuthenticationConverter;
import com.hpg.web.security.HpgJwtDecoders;
import com.hpg.web.security.ProblemDetailsSecurityHandlers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * payment-service validates merchant tokens itself (signature via merchant-service's JWKS, expiry, issuer).
 * It never trusts that a request came through the gateway.
 *
 * <p>Public routes, each protected by something other than a token:
 * <ul>
 *   <li>{@code /checkout/**}: the unguessable checkout token; the callback by the provider's signature.</li>
 *   <li>{@code /webhooks/**}: HMAC signature over the raw body.</li>
 * </ul>
 * CSRF protection is off: the API uses bearer tokens (no cookies), and the browser-facing callback is
 * authenticated by the provider's signature, which a cross-site attacker cannot forge.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain apiChain(HttpSecurity http, ProblemDetailsSecurityHandlers handlers,
            HpgJwtAuthenticationConverter jwtConverter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // The checkout page sets its own strict Content-Security-Policy.
                .headers(h -> h.frameOptions(f -> f.deny()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/checkout/**", "/webhooks/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/v1/payments", "/api/v1/payments/**").hasRole(Roles.MERCHANT)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(handlers.authenticationEntryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(handlers.authenticationEntryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()))
                .build();
    }

    /** Keys are fetched lazily from merchant-service and cached; unknown key ids trigger a refetch (rotation). */
    @Bean
    public JwtDecoder jwtDecoder(@Value("${hpg.security.jwk-set-uri}") String jwkSetUri,
            @Value("${hpg.security.issuer}") String issuer, ExternalIssuerProperties identityProvider) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        // Also people signed in through the identity provider (dashboard SSO), when one is configured.
        return HpgJwtDecoders.withExternalIssuer(decoder, issuer, identityProvider);
    }
}
