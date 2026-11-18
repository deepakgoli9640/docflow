package com.hpg.common.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Decides whether an address is a safe target for outbound requests we make on a merchant's behalf
 * (webhooks). Used by the save-time URL validator and the delivery-time guard so both apply the same rules.
 *
 * <p>Blocks the IPv4 ranges that reach private or reserved networks (RFC 6890 special-purpose registry), the
 * non-public IPv6 ranges, and IPv6 forms that carry an IPv4 address inside them: IPv4-compatible
 * ({@code ::a.b.c.d}), NAT64 ({@code 64:ff9b::/96}) and 6to4 ({@code 2002::/16}). Without that last step
 * {@code 64:ff9b::a9fe:a9fe} would reach the cloud metadata service (169.254.169.254) on an IPv6-only
 * network with NAT64.
 */
public final class IpAddresses {

    private IpAddresses() {
    }

    /** @return true if the address is loopback, private, link-local, reserved or otherwise not public */
    public static boolean isNonPublic(InetAddress address) {
        if (address instanceof Inet4Address) {
            return isNonPublicIpv4(address.getAddress());
        }
        if (address instanceof Inet6Address) {
            return isNonPublicIpv6(address);
        }
        return true;
    }

    private static boolean isNonPublicIpv4(byte[] b) {
        int a0 = b[0] & 0xFF;
        int a1 = b[1] & 0xFF;
        int a2 = b[2] & 0xFF;
        return a0 == 0                                   // 0.0.0.0/8 "this network"
                || a0 == 10                              // 10.0.0.0/8 private
                || (a0 == 100 && (a1 & 0xC0) == 64)      // 100.64.0.0/10 carrier-grade NAT
                || a0 == 127                             // 127.0.0.0/8 loopback
                || (a0 == 169 && a1 == 254)              // 169.254.0.0/16 link-local, cloud metadata
                || (a0 == 172 && (a1 & 0xF0) == 16)      // 172.16.0.0/12 private
                || (a0 == 192 && a1 == 0 && a2 == 0)     // 192.0.0.0/24 IETF protocol assignments
                || (a0 == 192 && a1 == 168)              // 192.168.0.0/16 private
                || (a0 == 198 && (a1 & 0xFE) == 18)      // 198.18.0.0/15 benchmarking
                || a0 >= 224;                            // multicast, reserved 240/4, broadcast
        // Documentation ranges (192.0.2/24, 198.51.100/24, 203.0.113/24) are unroutable but harmless.
    }

    private static boolean isNonPublicIpv6(InetAddress address) {
        byte[] b = address.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()
                || (b[0] & 0xFE) == 0xFC) {              // fc00::/7 unique local
            return true;
        }
        byte[] embedded = embeddedIpv4(b);
        return embedded != null && isNonPublicIpv4(embedded);
    }

    /** The IPv4 address an IPv6 address tunnels to, or null if it carries none. */
    private static byte[] embeddedIpv4(byte[] b) {
        if (allZero(b, 0, 12)) {                                         // ::a.b.c.d (IPv4-compatible)
            return slice(b, 12);
        }
        if (b[0] == 0x00 && b[1] == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B
                && allZero(b, 4, 12)) {                                  // 64:ff9b::/96 NAT64
            return slice(b, 12);
        }
        if (b[0] == 0x20 && b[1] == 0x02) {                              // 2002::/16 6to4
            return slice(b, 2);
        }
        return null;
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] slice(byte[] b, int from) {
        return new byte[] {b[from], b[from + 1], b[from + 2], b[from + 3]};
    }

    /** Parses an IP literal (no DNS lookup); brackets around IPv6 are accepted. */
    public static InetAddress parseLiteral(String literal) throws UnknownHostException {
        String s = literal.startsWith("[") && literal.endsWith("]") ? literal.substring(1, literal.length() - 1)
                : literal;
        if (!s.matches("[0-9.]+") && !s.contains(":")) {
            throw new UnknownHostException("not an IP literal: " + literal);
        }
        return InetAddress.getByName(s);
    }
}
