package com.hpg.merchant.domain;

import com.hpg.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerchantStatusTest {

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
            "PENDING_ACTIVATION, ACTIVE,      true",
            "PENDING_ACTIVATION, SUSPENDED,   false",
            "PENDING_ACTIVATION, DEACTIVATED, true",
            "ACTIVE,             SUSPENDED,   true",
            "ACTIVE,             ACTIVE,      false",
            "ACTIVE,             PENDING_ACTIVATION, false",
            "SUSPENDED,          ACTIVE,      true",
            "SUSPENDED,          DEACTIVATED, true",
            "DEACTIVATED,        ACTIVE,      false",
            "DEACTIVATED,        SUSPENDED,   false"
    })
    void transitions(MerchantStatus from, MerchantStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void onlyPendingAndActiveMerchantsMayLogIn() {
        assertThat(MerchantStatus.PENDING_ACTIVATION.allowsLogin()).isTrue();
        assertThat(MerchantStatus.ACTIVE.allowsLogin()).isTrue();
        assertThat(MerchantStatus.SUSPENDED.allowsLogin()).isFalse();
        assertThat(MerchantStatus.DEACTIVATED.allowsLogin()).isFalse();
    }

    @Test
    void merchantRejectsInvalidTransitionWithErrorCode() {
        Merchant merchant = Merchant.register("City Hospital Pvt Ltd", "City Hospital", "Billing@City.example", null);
        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.PENDING_ACTIVATION);
        assertThat(merchant.getContactEmail()).isEqualTo("billing@city.example");

        assertThatThrownBy(() -> merchant.changeStatus(MerchantStatus.SUSPENDED, "test"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(MerchantErrorCode.INVALID_STATE_TRANSITION));

        merchant.changeStatus(MerchantStatus.ACTIVE, "KYC ok");
        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(merchant.getStatusReason()).isEqualTo("KYC ok");
    }

    @Test
    void deactivatedMerchantCannotEditProfile() {
        Merchant merchant = Merchant.register("A", "A", "a@a.example", null);
        merchant.changeStatus(MerchantStatus.DEACTIVATED, "closed");
        assertThatThrownBy(() -> merchant.updateProfile("B", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(MerchantErrorCode.MERCHANT_DEACTIVATED));
    }
}
