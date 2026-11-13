package com.hpg.common.masking;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class LogMaskingTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "Receipt sent to asha.rao@example.com today | Receipt sent to a******o@example.com today",
            "Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.payload.sig | Authorization: Bearer ****",
            "header Basic aHBna19rZXk6c2VjcmV0dmFsdWU= rejected | header Basic **** rejected",
            "client_secret=s3cr3tValue&grant_type=x | client_secret=****&grant_type=x",
            "{\"keySecret\":\"abcd1234efgh\"} | {\"keySecret\":\"****\"}",
            "password: hunter2hunter | password: ****",
            "call +919800001234 now | call +91******1234 now",
            "card 4111 1111 1111 1111 declined | card [REDACTED-CARD] declined",
            "card 4111111111111111 declined | card [REDACTED-CARD] declined"})
    void redactsSensitiveValues(String in, String expected) {
        assertThat(LogMasking.mask(in)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "Payment 01a1d58b-1bcd-704f-849b-bc06111a6ebf captured",
            "BILL-PHONE-1794584025982 created",          // 13-digit epoch millis: fails Luhn
            "amount 20000.00 INR, attempts 3",
            "Merchant status changed merchantId=01a1d44c from=ACTIVE to=SUSPENDED",
            "TXN-20261113-000126 posted"})
    void leavesOrdinaryLogLinesAlone(String line) {
        assertThat(LogMasking.mask(line)).isEqualTo(line);
    }
}
