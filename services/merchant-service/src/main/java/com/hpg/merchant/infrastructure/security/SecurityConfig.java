package com.hpg.merchant.infrastructure.security;

import com.hpg.common.security.Roles;
import com.hpg.common.security.TokenClaims;
import com.hpg.merchant.application.ApiKeyService;
import com.hpg.web.security.HpgJwtAuthenticationConverter;
import com.hpg.web.security.ProblemDetailsSecurityHandlers;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Two filter chains in one service:
 * <ol>
 *   <li>The OAuth2 <b>authorization server</b> endpoints ({@code /oauth2/token}, {@code /oauth2/jwks}).</li>
 *   <li>The <b>resource server</b> protecting this service's own REST API with the tokens it issues.</li>
 * </ol>
 * Method-level {@code @PreAuthorize} on application services is a second layer under the URL rules.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerChain(HttpSecurity http, ProblemDetailsSecurityHandlers handlers)
            throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer = OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .csrf(csrf -> csrf.ignoringRequestMatchers(authorizationServer.getEndpointsMatcher()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers.authenticationEntryPoint()));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiChain(HttpSecurity http, ProblemDetailsSecurityHandlers handlers,
            HpgJwtAuthenticationConverter jwtConverter) throws Exception {
        http.securityMatcher("/**")
                // Pure bearer-token API: no cookies, no sessions, so CSRF protection does not apply.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/merchants").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole(Roles.OPS)
                        .requestMatchers("/internal/**").hasRole(Roles.SERVICE)
                        .requestMatchers("/api/v1/merchants/me", "/api/v1/merchants/me/**").hasRole(Roles.MERCHANT)
                        // Everything not listed above is denied: new endpoints are closed until opened deliberately.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(handlers.authenticationEntryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(handlers.authenticationEntryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()));
        return http.build();
    }

    /** Hashes are stored as {@code {bcrypt}$2a$...} so the algorithm can be upgraded later without a migration. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository(ApiKeyService apiKeys, SecurityProperties properties,
            PasswordEncoder passwordEncoder) {
        return new HpgRegisteredClientRepository(apiKeys, properties, passwordEncoder);
    }

    @Bean
    public OAuth2AuthorizationService authorizationService() {
        return new StatelessAuthorizationService();
    }

    @Bean
    public SigningKeys signingKeys(SecurityProperties properties) {
        return new SigningKeys(SigningKeyProvider.load(properties.jwt()), SigningKeyProvider.previous(properties.jwt()));
    }

    /** The JWKS: the active key plus retired public keys (rotation). Tokens name their key in the header (kid). */
    @Bean
    public JWKSource<SecurityContext> jwkSource(SigningKeys keys) {
        List<com.nimbusds.jose.jwk.JWK> all = new java.util.ArrayList<>();
        all.add(keys.active());
        all.addAll(keys.previous());
        return new ImmutableJWKSet<>(new JWKSet(all));
    }

    /** Active signing key and retired public keys. */
    public record SigningKeys(RSAKey active, List<RSAKey> previous) {
    }

    /** This service validates its own tokens locally (no HTTP call to itself), including the issuer. */
    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource, SecurityProperties properties,
            com.hpg.web.security.ExternalIssuerProperties identityProvider) {
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) org.springframework.security.oauth2.server.authorization
                .config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        // People signed in through the identity provider (dashboard SSO) use the same APIs as API-key clients.
        return com.hpg.web.security.HpgJwtDecoders.withExternalIssuer(decoder, properties.issuer(), identityProvider);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(SecurityProperties properties) {
        return AuthorizationServerSettings.builder().issuer(properties.issuer()).build();
    }

    /** Adds {@code roles} and, for merchant keys, {@code mid} to access tokens. */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer(ApiKeyService apiKeys, SigningKeys keys) {
        return context -> {
            // Sign with the active key only; the JWKS may also hold retired public keys.
            context.getJwsHeader().keyId(keys.active().getKeyID());
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())
                    || !AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
                return;
            }
            var settings = context.getRegisteredClient().getClientSettings();
            List<String> roles = settings.getSetting(HpgRegisteredClientRepository.SETTING_ROLES);
            context.getClaims().claim(TokenClaims.ROLES, roles);
            String merchantId = settings.getSetting(HpgRegisteredClientRepository.SETTING_MERCHANT_ID);
            if (merchantId != null) {
                context.getClaims().claim(TokenClaims.MERCHANT_ID, merchantId);
                apiKeys.recordUsage(context.getRegisteredClient().getClientId());
            }
        };
    }
}
