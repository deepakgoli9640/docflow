package com.hpg.merchant.application;

import com.hpg.common.error.BusinessException;
import com.hpg.common.money.Money;
import com.hpg.merchant.domain.CaptureMode;
import com.hpg.merchant.domain.MerchantErrorCode;
import com.hpg.merchant.domain.PaymentMethod;
import com.hpg.merchant.domain.PaymentSettings;
import com.hpg.merchant.infrastructure.config.MerchantProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Pure unit test of the validation rules: no Spring context, no database. */
class MerchantConfigurationValidationTest {

    private final MerchantProperties properties = new MerchantProperties("INR", 2, false,
            new MerchantProperties.Limits(new BigDecimal("1.00"), new BigDecimal("500000.00"),
                    new BigDecimal("5000000.00"), 5, 1440, 5, 180),
            new MerchantProperties.Defaults(Set.of("CARD", "UPI"), "AUTO", new BigDecimal("1.00"),
                    new BigDecimal("200000.00"), new BigDecimal("1000000.00"), 60, 3, 180, true, false));

    private final MerchantConfigurationService service = new MerchantConfigurationService(
            mock(com.hpg.merchant.infrastructure.persistence.MerchantConfigurationRepository.class),
            mock(MerchantLookup.class), properties, mock(AuditLogger.class), mock(MerchantSnapshots.class));

    private static PaymentSettings settings(String min, String max, String daily, int ttl, String webhook) {
        return new PaymentSettings(Set.of(PaymentMethod.UPI), CaptureMode.MANUAL, Money.inr(min), Money.inr(max),
                Money.inr(daily), ttl, 3, 30, webhook, true, false);
    }

    @Test
    void validSettingsPass() {
        assertThatCode(() -> service.validate(settings("10.00", "100000.00", "1000000.00", 30,
                "https://his.example/hooks"))).doesNotThrowAnyException();
    }

    @Test
    void defaultsAreThemselvesValid() {
        assertThatCode(() -> service.validate(service.defaultSettings())).doesNotThrowAnyException();
        assertThat(service.defaultSettings().enabledMethods()).containsExactlyInAnyOrder(PaymentMethod.CARD, PaymentMethod.UPI);
    }

    @Test
    void rejectsPerTransactionLimitAbovePlatformMaximum() {
        assertError(settings("1.00", "500000.01", "5000000.00", 30, null), MerchantErrorCode.LIMIT_EXCEEDS_PLATFORM_MAXIMUM);
    }

    @Test
    void rejectsDailyLimitAbovePlatformMaximum() {
        assertError(settings("1.00", "1000.00", "5000000.01", 30, null), MerchantErrorCode.LIMIT_EXCEEDS_PLATFORM_MAXIMUM);
    }

    @Test
    void rejectsInconsistentLimits() {
        assertError(settings("500.00", "100.00", "1000.00", 30, null), MerchantErrorCode.INVALID_LIMITS);
        assertError(settings("1.00", "2000.00", "1000.00", 30, null), MerchantErrorCode.INVALID_LIMITS);
        assertError(settings("0.50", "100.00", "1000.00", 30, null), MerchantErrorCode.INVALID_LIMITS);
    }

    @Test
    void rejectsTtlOutsidePlatformRange() {
        assertError(settings("1.00", "100.00", "1000.00", 2, null), MerchantErrorCode.LIMIT_EXCEEDS_PLATFORM_MAXIMUM);
        assertError(settings("1.00", "100.00", "1000.00", 1441, null), MerchantErrorCode.LIMIT_EXCEEDS_PLATFORM_MAXIMUM);
    }

    @Test
    void rejectsOtherCurrencies() {
        PaymentSettings usd = new PaymentSettings(Set.of(PaymentMethod.CARD), CaptureMode.AUTO, Money.of("1.00", "USD"),
                Money.of("100.00", "USD"), Money.of("1000.00", "USD"), 60, 3, 30, null, true, false);
        assertError(usd, MerchantErrorCode.UNSUPPORTED_CURRENCY);
    }

    @Test
    void rejectsUnsafeWebhook() {
        assertError(settings("1.00", "100.00", "1000.00", 30, "http://his.example/hook"), MerchantErrorCode.INVALID_WEBHOOK_URL);
    }

    private void assertError(PaymentSettings s, MerchantErrorCode expected) {
        assertThatThrownBy(() -> service.validate(s))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(expected));
    }
}
