package com.hpg.merchant.application;

import com.hpg.common.error.BusinessException;
import com.hpg.merchant.domain.MerchantErrorCode;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * First line of defence against Server-Side Request Forgery (SSRF): a merchant must not be able to
 * make our notification-service send requests to our own internal network (metadata endpoints,
 * databases, admin ports).
 *
 * <p>This check only sees the URL as written. A hostname that <em>resolves</em> to a private address
 * (or changes resolution later — DNS rebinding) must also be blocked at delivery time; that check
 * belongs to notification-service (Phase 4).
 */
public class WebhookUrlValidator {

    private final boolean allowInsecure;

    public WebhookUrlValidator(boolean allowInsecure) {
        this.allowInsecure = allowInsecure;
    }

    public void validate(String url) {
        if (url == null) {
            return; // webhooks are optional
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw invalid("Webhook URL is not a valid URI.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowInsecure && scheme.equals("http"))) {
            throw invalid("Webhook URL must use https.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw invalid("Webhook URL must have a host.");
        }
        if (uri.getUserInfo() != null) {
            throw invalid("Webhook URL must not contain credentials.");
        }
        if (uri.getFragment() != null) {
            throw invalid("Webhook URL must not contain a fragment.");
        }
        if (!allowInsecure && isInternalHost(uri.getHost())) {
            throw invalid("Webhook URL must not point to a private, loopback or link-local address.");
        }
    }

    private static boolean isInternalHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".internal") || h.endsWith(".local")) {
            return true;
        }
        if (!looksLikeIpLiteral(h)) {
            return false; // hostname: resolved and checked at delivery time
        }
        try {
            // For an IP literal this does not perform a DNS lookup.
            InetAddress address = InetAddress.getByName(h.replace("[", "").replace("]", ""));
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress() || address.isMulticastAddress() || isUniqueLocalIpv6(address);
        } catch (java.net.UnknownHostException e) {
            return true; // unparseable literal: reject
        }
    }

    private static boolean looksLikeIpLiteral(String host) {
        return host.startsWith("[") || host.contains(":") || host.matches("[0-9.]+");
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC; // fc00::/7
    }

    private static BusinessException invalid(String detail) {
        return new BusinessException(MerchantErrorCode.INVALID_WEBHOOK_URL, detail);
    }
}
