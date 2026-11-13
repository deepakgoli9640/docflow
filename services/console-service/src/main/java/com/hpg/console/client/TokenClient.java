package com.hpg.console.client;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Exchanges a client id and secret for an access token at the gateway's token endpoint.
 *
 * <p>This is also how the console authenticates a person: if the exchange succeeds, the credentials
 * are good, and the token says which role they have. The console therefore keeps no user store of its
 * own and cannot disagree with merchant-service about who may do what.
 */
public class TokenClient {

    private final RestClient http;

    public TokenClient(RestClient http) {
        this.http = http;
    }

    public record Token(String value, Duration expiresIn) {

        @Override
        public String toString() {
            return "Token[****, expiresIn=" + expiresIn + "]";
        }
    }

    /** @throws InvalidCredentialsException if the gateway rejected the client id or secret */
    public Token exchange(String clientId, String clientSecret) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = http.post().uri("/oauth2/token")
                    .headers(headers -> headers.setBasicAuth(clientId, clientSecret, StandardCharsets.UTF_8))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("access_token") == null) {
                throw new IllegalStateException("Token endpoint returned no access_token");
            }
            long expiresIn = response.get("expires_in") instanceof Number number ? number.longValue() : 300;
            return new Token(String.valueOf(response.get("access_token")), Duration.ofSeconds(expiresIn));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 400) {
                throw new InvalidCredentialsException();
            }
            throw e;
        }
    }

    /** Wrong credentials, a revoked key, or a suspended merchant — the console cannot tell, and says so. */
    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("The gateway rejected these credentials");
        }
    }
}
