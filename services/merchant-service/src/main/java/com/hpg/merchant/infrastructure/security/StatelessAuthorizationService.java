package com.hpg.merchant.infrastructure.security;

import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

/**
 * Does not store issued tokens.
 *
 * <p>Spring Authorization Server's default in-memory store keeps every issued authorization forever:
 * with client-credentials tokens requested every 15 minutes by every hospital, that is an unbounded
 * memory leak. We issue self-contained JWTs that resource servers validate by signature, and we do not
 * expose token introspection or revocation endpoints, so there is nothing to look tokens up for.
 * Revocation is handled by short TTLs plus the merchant deny-list at the gateway.
 */
public class StatelessAuthorizationService implements OAuth2AuthorizationService {

    @Override
    public void save(OAuth2Authorization authorization) {
        // intentionally not stored, see class comment
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        // nothing stored
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return null;
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        return null;
    }
}
