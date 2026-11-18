package com.hpg.notification.delivery;

import com.hpg.common.net.IpAddresses;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * SSRF guard at delivery time. merchant-service validates the URL when it is saved, but DNS can change
 * afterwards (DNS rebinding), so the resolved addresses are checked again right before every delivery:
 * no loopback, private, link-local (cloud metadata), reserved or multicast targets (see {@link IpAddresses}),
 * https only.
 * {@code allowInsecure} (local development) permits http and private addresses.
 */
public class WebhookUrlGuard {

    private final boolean allowInsecure;
    private final HostResolver resolver;

    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public WebhookUrlGuard(boolean allowInsecure) {
        this(allowInsecure, InetAddress::getAllByName);
    }

    public WebhookUrlGuard(boolean allowInsecure, HostResolver resolver) {
        this.allowInsecure = allowInsecure;
        this.resolver = resolver;
    }

    /** @throws UnsafeWebhookUrlException if delivery to this URL must not happen */
    public URI check(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new UnsafeWebhookUrlException("not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowInsecure && scheme.equals("http"))) {
            throw new UnsafeWebhookUrlException("webhook URLs must use https");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw new UnsafeWebhookUrlException("webhook URL must have a host and no credentials");
        }
        if (allowInsecure) {
            return uri;
        }
        try {
            for (InetAddress address : resolver.resolve(uri.getHost())) {
                if (IpAddresses.isNonPublic(address)) {
                    throw new UnsafeWebhookUrlException("webhook host resolves to a non-public address");
                }
            }
        } catch (UnknownHostException e) {
            throw new UnsafeWebhookUrlException("webhook host does not resolve");
        }
        return uri;
    }

    public static class UnsafeWebhookUrlException extends RuntimeException {
        public UnsafeWebhookUrlException(String message) {
            super(message);
        }
    }
}
