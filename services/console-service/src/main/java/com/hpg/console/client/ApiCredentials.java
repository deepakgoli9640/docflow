package com.hpg.console.client;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;

/**
 * The credentials behind one signed-in session, and the access token they produce.
 *
 * <p>These live in the <b>server-side session</b> and are never sent to the browser, which only ever
 * holds a session cookie. That is the whole point of putting a backend-for-frontend in front of the
 * APIs: an access token in JavaScript can be read by any script that gets onto the page.
 *
 * <p>Tokens last fifteen minutes, so the secret is kept to mint the next one rather than asking the
 * person to sign in again every quarter of an hour. A margin before expiry avoids handing out a token
 * that dies in flight.
 */
public class ApiCredentials implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private final String clientId;
    private final String clientSecret;
    private transient TokenClient tokenClient;
    private transient String cachedToken;
    private transient Instant cachedUntil = Instant.EPOCH;

    public ApiCredentials(String clientId, String clientSecret, TokenClient tokenClient) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.tokenClient = tokenClient;
    }

    public synchronized String accessToken() {
        if (cachedToken != null && Instant.now().isBefore(cachedUntil)) {
            return cachedToken;
        }
        TokenClient.Token token = tokenClient.exchange(clientId, clientSecret);
        cachedToken = token.value();
        cachedUntil = Instant.now().plus(token.expiresIn()).minus(EXPIRY_MARGIN);
        return cachedToken;
    }

    /** After deserialisation (a persisted session) the client has to be handed back in. */
    public synchronized void bind(TokenClient tokenClient) {
        this.tokenClient = tokenClient;
    }

    public String clientId() {
        return clientId;
    }

    @Override
    public String toString() {
        return "ApiCredentials[clientId=" + clientId + ", clientSecret=****]";
    }
}
