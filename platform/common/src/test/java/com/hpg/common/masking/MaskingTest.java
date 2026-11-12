package com.hpg.common.masking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingTest {

    @Test
    void masksEmail() {
        assertThat(Masking.email("patient@example.com")).isEqualTo("p*****t@example.com");
        assertThat(Masking.email("ab@x.io")).isEqualTo("a*@x.io");
        assertThat(Masking.email("not-an-email")).isEqualTo("****");
        assertThat(Masking.email(null)).isNull();
    }

    @Test
    void masksPhoneKeepingCountryCodeAndLastFour() {
        assertThat(Masking.phone("+919800001234")).isEqualTo("+91******1234");
        assertThat(Masking.phone("9800001234")).isEqualTo("******1234");
        assertThat(Masking.phone("123")).isEqualTo("****");
    }

    @Test
    void masksSecrets() {
        assertThat(Masking.secret("hpgk_ABCDEFGH")).isEqualTo("hpgk****");
        assertThat(Masking.secret("abc")).isEqualTo("****");
    }
}
