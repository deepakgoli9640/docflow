package com.hpg.merchant.infrastructure.security;

import com.hpg.common.security.Roles;
import com.hpg.merchant.application.ApiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tells Spring Authorization Server which OAuth2 clients exist.
 *
 * <ul>
 *   <li><b>Merchant API keys</b> come from the {@code merchant_credentials} table on every lookup, so a
 *       revoked key or suspended merchant cannot obtain a new token from the very next request.</li>
 *   <li><b>Static clients</b> (ops console, internal services) come from configuration with secrets
 *       supplied via environment variables. A static client with no secret is skipped, so there are
 *       never default credentials.</li>
 * </ul>
 *
 * Only the {@code client_credentials} grant is allowed: these are server-to-server clients, there is no
 * browser login flow.
 */
public class HpgRegisteredClientRepository implements RegisteredClientRepository {

    static final String SETTING_MERCHANT_ID = "hpg.merchant-id";
    static final String SETTING_ROLES = "hpg.roles";
    private static final Logger log = LoggerFactory.getLogger(HpgRegisteredClientRepository.class);

    private final ApiKeyService apiKeys;
    private final Duration accessTokenTtl;
    private final Map<String, RegisteredClient> staticClients = new HashMap<>();

    public HpgRegisteredClientRepository(ApiKeyService apiKeys, SecurityProperties properties,
            PasswordEncoder passwordEncoder) {
        this.apiKeys = apiKeys;
        this.accessTokenTtl = properties.accessTokenTtl();
        for (SecurityProperties.StaticClient client : properties.staticClients()) {
            if (client.clientSecret() == null || client.clientSecret().isBlank()) {
                log.warn("Static client '{}' has no secret configured and is disabled", client.clientId());
                continue;
            }
            staticClients.put(client.clientId(), build(client.clientId(), client.clientId(),
                    passwordEncoder.encode(client.clientSecret()), null, client.roles()));
        }
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        throw new UnsupportedOperationException("Clients are managed through the API key endpoints");
    }

    @Override
    public RegisteredClient findById(String id) {
        RegisteredClient staticClient = staticClients.get(id);
        return staticClient != null ? staticClient : findByClientId(id);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        RegisteredClient staticClient = staticClients.get(clientId);
        if (staticClient != null) {
            return staticClient;
        }
        return apiKeys.findClient(clientId)
                .map(c -> build(c.keyId(), c.keyId(), c.secretHash(), c.merchantId().toString(), List.of(Roles.MERCHANT)))
                .orElse(null);
    }

    private RegisteredClient build(String id, String clientId, String encodedSecret, String merchantId,
            List<String> roles) {
        ClientSettings.Builder settings = ClientSettings.builder().setting(SETTING_ROLES, List.copyOf(roles));
        if (merchantId != null) {
            settings.setting(SETTING_MERCHANT_ID, merchantId);
        }
        return RegisteredClient.withId(id)
                .clientId(clientId)
                .clientSecret(encodedSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientSettings(settings.build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .accessTokenTimeToLive(accessTokenTtl)
                        .build())
                .build();
    }
}
