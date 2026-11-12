package com.hpg.payment.application;

import com.hpg.common.error.NotFoundException;
import com.hpg.payment.domain.Payment;
import com.hpg.payment.domain.PaymentErrorCode;
import com.hpg.payment.infrastructure.persistence.PaymentRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class PaymentLookup {

    private final PaymentRepository payments;

    PaymentLookup(PaymentRepository payments) {
        this.payments = payments;
    }

    /** Merchant-scoped: another merchant's payment is indistinguishable from a missing one. */
    Payment requireOwned(UUID merchantId, UUID paymentId) {
        return payments.findByIdAndMerchantId(paymentId, merchantId).orElseThrow(() -> notFound(paymentId));
    }

    Payment require(UUID paymentId) {
        return payments.findById(paymentId).orElseThrow(() -> notFound(paymentId));
    }

    private static NotFoundException notFound(UUID paymentId) {
        return new NotFoundException(PaymentErrorCode.PAYMENT_NOT_FOUND, "Payment " + paymentId + " not found.");
    }
}
