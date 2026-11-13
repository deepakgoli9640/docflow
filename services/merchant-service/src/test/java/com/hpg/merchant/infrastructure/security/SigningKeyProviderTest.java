package com.hpg.merchant.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningKeyProviderTest {

    @Test
    void productionRefusesToStartWithoutAConfiguredKey() {
        assertThatThrownBy(() -> SigningKeyProvider.load(new SecurityProperties.Jwt(null, null, List.of(), true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No JWT signing key configured");
    }

    @Test
    void developmentGetsAnEphemeralKey() {
        var key = SigningKeyProvider.load(new SecurityProperties.Jwt(null, null, List.of(), false));
        assertThat(key.isPrivate()).isTrue();
        assertThat(key.getKeyID()).isNotBlank();
    }
}
