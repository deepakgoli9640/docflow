package com.hpg.common.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IpAddressesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "0.0.0.0", "0.1.2.3", "10.0.0.1", "100.64.0.1", "100.127.255.255", "127.0.0.1", "127.255.0.1",
            "169.254.169.254", "172.16.0.1", "172.31.255.255", "192.0.0.8", "192.168.0.1", "198.18.0.1",
            "198.19.255.255", "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "::", "::1", "fe80::1", "fec0::1", "fc00::1", "fd12:3456::1", "ff02::1",
            "::ffff:127.0.0.1",          // IPv4-mapped
            "::7f00:1",                  // IPv4-compatible 127.0.0.1
            "64:ff9b::a9fe:a9fe",        // NAT64 169.254.169.254
            "64:ff9b::10.0.0.1",
            "2002:a9fe:a9fe::",          // 6to4 169.254.169.254
            "2002:c0a8:101::1"})         // 6to4 192.168.1.1
    void nonPublic(String ip) throws UnknownHostException {
        assertThat(IpAddresses.isNonPublic(InetAddress.getByName(ip))).as(ip).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "8.8.8.8", "1.1.1.1", "100.63.255.255", "100.128.0.1", "172.15.0.1", "172.32.0.1",
            "192.0.2.10", "203.0.113.10", "223.255.255.255",
            "2606:4700:4700::1111", "64:ff9b::808:808", "2002:808:808::"})
    void publicAddresses(String ip) throws UnknownHostException {
        assertThat(IpAddresses.isNonPublic(InetAddress.getByName(ip))).as(ip).isFalse();
    }

    @Test
    void parseLiteralAcceptsBracketsAndRefusesHostnames() throws UnknownHostException {
        assertThat(IpAddresses.parseLiteral("[::1]").isLoopbackAddress()).isTrue();
        assertThat(IpAddresses.parseLiteral("10.0.0.1").getHostAddress()).isEqualTo("10.0.0.1");
        assertThatThrownBy(() -> IpAddresses.parseLiteral("example.com")).isInstanceOf(UnknownHostException.class);
    }
}
