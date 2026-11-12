package com.hpg.merchant.application;

import com.hpg.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookUrlValidatorTest {

    private final WebhookUrlValidator strict = new WebhookUrlValidator(false);
    private final WebhookUrlValidator local = new WebhookUrlValidator(true);

    @Test
    void acceptsPublicHttpsUrlAndNull() {
        assertThatCode(() -> strict.validate("https://his.cityhospital.example/hpg/webhooks")).doesNotThrowAnyException();
        assertThatCode(() -> strict.validate(null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://his.cityhospital.example/hook",      // not https
            "ftp://his.cityhospital.example/hook",
            "https://localhost/hook",
            "https://127.0.0.1/hook",
            "https://10.0.0.5/hook",
            "https://192.168.1.10/hook",
            "https://172.16.0.1/hook",
            "https://169.254.169.254/latest/meta-data",  // cloud metadata endpoint
            "https://[::1]/hook",
            "https://[fd00::1]/hook",
            "https://0.0.0.0/hook",
            "https://metadata.google.internal/hook",
            "https://user:pass@his.example/hook",
            "not a url",
            "https:///nohost"
    })
    void rejectsUnsafeUrlsInStrictMode(String url) {
        assertThatThrownBy(() -> strict.validate(url)).isInstanceOf(BusinessException.class);
    }

    @Test
    void localModeAllowsHttpAndLocalhostForDevelopment() {
        assertThatCode(() -> local.validate("http://localhost:9999/hook")).doesNotThrowAnyException();
        assertThatThrownBy(() -> local.validate("ftp://localhost/hook")).isInstanceOf(BusinessException.class);
    }
}
