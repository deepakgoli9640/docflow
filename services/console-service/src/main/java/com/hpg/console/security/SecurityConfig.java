package com.hpg.console.security;

import com.hpg.common.security.Roles;
import com.hpg.console.client.TokenClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * A browser session, not a bearer token.
 *
 * <p>The browser holds a session cookie and nothing else; the access token never leaves the server.
 * That means the usual browser defences apply and matter:
 * <ul>
 *   <li><b>CSRF protection is on.</b> A cookie-authenticated app without it lets any other site submit
 *       forms as the signed-in person — "capture this payment" among them.</li>
 *   <li><b>The session id is replaced on sign-in</b> (Spring's default), so a fixed session id planted
 *       beforehand is worthless.</li>
 *   <li><b>A strict content security policy</b>, because this console shows payment data.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain consoleChain(HttpSecurity http, AuthenticationManager authenticationManager)
            throws Exception {
        CsrfTokenRequestAttributeHandler csrf = new CsrfTokenRequestAttributeHandler();
        return http
                .authenticationManager(authenticationManager)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/css/**", "/js/**", "/favicon.ico").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/ops/**").hasRole(Roles.OPS)
                        .requestMatchers("/payments/**", "/settings/**", "/api-keys/**").hasRole(Roles.MERCHANT)
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "POST"))
                        .logoutSuccessUrl("/login?loggedOut")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                .csrf(it -> it.csrfTokenRepository(new CookieCsrfTokenRepository())
                        .csrfTokenRequestHandler(csrf))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentSecurityPolicy(policy -> policy.policyDirectives(
                                "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' data:; "
                                        + "form-action 'self'; frame-ancestors 'none'; base-uri 'none'")))
                .build();
    }

    @Bean
    public AuthenticationManager authenticationManager(TokenClient tokenClient, JwtDecoder jwtDecoder) {
        ProviderManager manager = new ProviderManager(new ConsoleAuthenticationProvider(tokenClient, jwtDecoder));
        // The credentials are needed after sign-in, to mint the next access token.
        manager.setEraseCredentialsAfterAuthentication(false);
        return manager;
    }

    /** Verifies tokens with merchant-service's published keys, fetched through the gateway. */
    @Bean
    public JwtDecoder jwtDecoder(@Value("${hpg.console.gateway-uri}") String gatewayUri,
            @Value("${hpg.security.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(gatewayUri + "/oauth2/jwks").build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
}
