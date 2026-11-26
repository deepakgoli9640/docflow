package com.hpg.gateway.config;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * The client address used for per-IP rate limits (token and registration endpoints).
 *
 * <p>With {@code trustedProxies = 0} (the default) it is the TCP peer address. Behind proxies that peer is
 * the proxy, so every client would share one bucket. With {@code trustedProxies = n}, the gateway trusts the
 * last {@code n} hops: each trusted proxy appends the address it received the connection from to
 * {@code X-Forwarded-For}, so the client is the n-th entry from the right. Entries further left are written
 * by the client itself and are ignored, so a forged header cannot pick someone else's bucket.
 *
 * <p>Cloud Run: Google's front end appends the caller's address, so {@code trustedProxies = 1}.
 */
public final class ClientIpResolver {

    static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final int trustedProxies;

    public ClientIpResolver(int trustedProxies) {
        if (trustedProxies < 0) {
            throw new IllegalArgumentException("trustedProxies must be >= 0");
        }
        this.trustedProxies = trustedProxies;
    }

    public String resolve(ServerHttpRequest request) {
        if (trustedProxies > 0) {
            List<String> hops = forwardedHops(request.getHeaders().get(X_FORWARDED_FOR));
            if (hops.size() >= trustedProxies) {
                return hops.get(hops.size() - trustedProxies);
            }
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : "unknown";
    }

    /** All entries of every X-Forwarded-For header, in order, trimmed, empty ones dropped. */
    private static List<String> forwardedHops(List<String> headerValues) {
        List<String> hops = new ArrayList<>();
        if (headerValues == null) {
            return hops;
        }
        for (String value : headerValues) {
            for (String part : value.split(",")) {
                String hop = part.trim();
                if (!hop.isEmpty()) {
                    hops.add(hop);
                }
            }
        }
        return hops;
    }
}
