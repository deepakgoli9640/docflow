package com.hpg.notification.delivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivateNetworkGuardTest {

    private final PrivateNetworkGuard strict = new PrivateNetworkGuard(false);
    private final PrivateNetworkGuard local = new PrivateNetworkGuard(true);

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/hook",
            "https://10.1.2.3/hook",
            "https://192.168.0.5/hook",
            "https://172.16.4.4/hook",
            "https://169.254.169.254/latest/meta-data",  // cloud instance metadata
            "https://100.64.0.1/hook",                   // carrier-grade NAT
            "https://[::1]/hook",
            "https://[fd12::1]/hook",
            "http://example.com/hook",                   // not https
            "https:///nohost"
    })
    void refusesAnythingThatIsNotAPublicHttpsEndpoint(String url) {
        assertThatThrownBy(() -> strict.check(URI.create(url)))
                .isInstanceOf(PrivateNetworkGuard.BlockedTargetException.class);
    }

    @Test
    void allowsAPublicHttpsEndpoint() {
        // An address literal, so the test does not depend on DNS being available where it runs.
        assertThatCode(() -> strict.check(URI.create("https://8.8.8.8/hpg/webhooks"))).doesNotThrowAnyException();
    }

    @Test
    void unresolvableHostIsRetryableRatherThanBlocked() {
        assertThatThrownBy(() -> strict.check(URI.create("https://no-such-host.invalid/hook")))
                .isInstanceOf(PrivateNetworkGuard.UnresolvableTargetException.class);
    }

    @Test
    void localModeAllowsHttpAndLocalhostForDevelopment() {
        assertThatCode(() -> local.check(URI.create("http://localhost:9999/hook"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> local.check(URI.create("ftp://localhost/hook")))
                .isInstanceOf(PrivateNetworkGuard.BlockedTargetException.class);
    }

    @Test
    void classifiesAddressRanges() throws Exception {
        assertThat(PrivateNetworkGuard.isPrivate(InetAddress.getByName("8.8.8.8"))).isFalse();
        assertThat(PrivateNetworkGuard.isPrivate(InetAddress.getByName("10.0.0.1"))).isTrue();
        assertThat(PrivateNetworkGuard.isPrivate(InetAddress.getByName("169.254.1.1"))).isTrue();
        assertThat(PrivateNetworkGuard.isPrivate(InetAddress.getByName("100.127.255.255"))).isTrue();
        assertThat(PrivateNetworkGuard.isPrivate(InetAddress.getByName("101.0.0.1"))).isFalse();
    }
}
