package com.hpg.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientIpResolverTest {

    private static final InetSocketAddress PROXY = new InetSocketAddress("35.191.0.10", 443);

    private static MockServerHttpRequest request(String... forwardedFor) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/oauth2/token").remoteAddress(PROXY);
        for (String value : forwardedFor) {
            builder.header(ClientIpResolver.X_FORWARDED_FOR, value);
        }
        return builder.build();
    }

    @Test
    void withoutTrustedProxiesTheHeaderIsIgnored() {
        assertThat(new ClientIpResolver(0).resolve(request("203.0.113.7"))).isEqualTo("35.191.0.10");
    }

    @Test
    void oneTrustedProxyTakesTheAddressItAppended() {
        assertThat(new ClientIpResolver(1).resolve(request("203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    void aForgedLeadingEntryIsIgnored() {
        // The client sent "X-Forwarded-For: 1.2.3.4"; the trusted proxy appended the real address.
        assertThat(new ClientIpResolver(1).resolve(request("1.2.3.4, 203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    void twoTrustedProxiesSkipTheInnerOne() {
        assertThat(new ClientIpResolver(2).resolve(request("1.2.3.4, 203.0.113.7, 10.0.0.5")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void entriesAcrossSeveralHeadersAreReadInOrder() {
        assertThat(new ClientIpResolver(1).resolve(request("1.2.3.4", " 203.0.113.7 "))).isEqualTo("203.0.113.7");
    }

    @Test
    void tooFewEntriesFallBackToThePeer() {
        assertThat(new ClientIpResolver(2).resolve(request("203.0.113.7"))).isEqualTo("35.191.0.10");
        assertThat(new ClientIpResolver(1).resolve(request())).isEqualTo("35.191.0.10");
    }

    @Test
    void negativeCountIsRejected() {
        assertThatThrownBy(() -> new ClientIpResolver(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
