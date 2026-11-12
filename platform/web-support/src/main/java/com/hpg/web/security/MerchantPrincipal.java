package com.hpg.web.security;

import java.util.UUID;

/**
 * The merchant a request acts for, taken from the validated JWT's {@code mid} claim.
 *
 * <p>Controllers receive it as a method parameter. The merchant id is <em>never</em> read from the URL
 * or body for merchant APIs, which is what makes cross-tenant access (IDOR) structurally impossible.
 *
 * @param merchantId merchant the token was issued for
 * @param clientId   API key id (token subject) that authenticated
 */
public record MerchantPrincipal(UUID merchantId, String clientId) {
}
