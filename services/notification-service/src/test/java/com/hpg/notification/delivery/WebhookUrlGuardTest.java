package com.hpg.notification.delivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookUrlGuardTest {

    static WebhookUrlGuard resolvingTo(String ip) {
        return new WebhookUrlGuard(false, host -> new InetAddress[] {InetAddress.getByName(ip)});
    }

    @Test
    void allowsHttpsToPublicAddresses() {
        assertThat(resolvingTo("203.0.113.10").check("https://his.hospital.example/hooks").getHost())
                .isEqualTo("his.hospital.example");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.5", "192.168.1.1", "169.254.169.254", "0.0.0.0",
            "100.64.0.1", "::1", "fd00::1", "fe80::1", "224.0.0.1"})
    void blocksNonPublicTargetsEvenIfDnsChangedAfterRegistration(String ip) {
        assertThatThrownBy(() -> resolvingTo(ip).check("https://his.hospital.example/hooks"))
                .isInstanceOf(WebhookUrlGuard.UnsafeWebhookUrlException.class);
    }

    @Test
    void requiresHttpsAndNoCredentials() {
        assertThatThrownBy(() -> resolvingTo("203.0.113.10").check("http://his.hospital.example/hooks"))
                .hasMessageContaining("https");
        assertThatThrownBy(() -> resolvingTo("203.0.113.10").check("https://user:pw@his.hospital.example/hooks"))
                .hasMessageContaining("credentials");
        assertThatThrownBy(() -> resolvingTo("203.0.113.10").check("not a url"))
                .isInstanceOf(WebhookUrlGuard.UnsafeWebhookUrlException.class);
    }

    @Test
    void unresolvableHostIsBlocked() {
        WebhookUrlGuard guard = new WebhookUrlGuard(false, host -> {
            throw new UnknownHostException(host);
        });
        assertThatThrownBy(() -> guard.check("https://nowhere.example/hooks")).hasMessageContaining("resolve");
    }

    @Test
    void insecureModeAllowsLocalHttpForDevelopment() {
        assertThat(new WebhookUrlGuard(true).check("http://localhost:9999/hooks").getPort()).isEqualTo(9999);
    }
}
