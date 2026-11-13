package com.hpg.notification.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Second line of defence against Server-Side Request Forgery, at the moment of delivery.
 *
 * <p>merchant-service already rejects a webhook URL that obviously points inside our network. That check
 * sees only the text of the URL: a hostname that looked innocent can be made to resolve to
 * {@code 169.254.169.254} or {@code 10.0.0.5} later (DNS rebinding), and the attacker controls the DNS
 * record, not us. So the host is resolved here, immediately before the request, and every address it
 * resolves to must be public.
 *
 * <p>This is not airtight on its own — a name can resolve differently between this check and the
 * connection. In production the dependable control is egress filtering: notification-service should only
 * be allowed to open outbound connections through a proxy that enforces the same rule.
 */
public final class PrivateNetworkGuard {

    private static final Logger log = LoggerFactory.getLogger(PrivateNetworkGuard.class);

    private final boolean allowPrivateTargets;

    public PrivateNetworkGuard(boolean allowPrivateTargets) {
        this.allowPrivateTargets = allowPrivateTargets;
        if (allowPrivateTargets) {
            log.warn("Webhook delivery to private addresses is ENABLED (local development only).");
        }
    }

    /** @throws BlockedTargetException if the URL is not safe to call */
    public void check(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean schemeAllowed = scheme.equals("https") || (allowPrivateTargets && scheme.equals("http"));
        if (!schemeAllowed) {
            throw new BlockedTargetException("webhook URL must use https (got '" + scheme + "')");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BlockedTargetException("webhook URL has no host");
        }
        if (allowPrivateTargets) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // Unresolvable is retryable, not a security failure: DNS may simply be down.
            throw new UnresolvableTargetException("cannot resolve " + host);
        }
        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                throw new BlockedTargetException("webhook host " + host + " resolves to the non-public address "
                        + address.getHostAddress());
            }
        }
    }

    static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            // 100.64.0.0/10 (carrier-grade NAT) and 192.0.0.0/24 are not covered by the checks above.
            return (first == 100 && second >= 64 && second <= 127) || (first == 192 && second == 0 && (bytes[2] & 0xFF) == 0);
        }
        // IPv6 unique local addresses: fc00::/7
        return (bytes[0] & 0xFE) == 0xFC;
    }

    /** Never retried: the configuration is wrong and someone has to change it. */
    public static class BlockedTargetException extends RuntimeException {
        public BlockedTargetException(String message) {
            super(message);
        }
    }

    /** Retried: DNS may recover. */
    public static class UnresolvableTargetException extends RuntimeException {
        public UnresolvableTargetException(String message) {
            super(message);
        }
    }
}
