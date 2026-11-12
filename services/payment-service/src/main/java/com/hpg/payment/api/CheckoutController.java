package com.hpg.payment.api;

import com.hpg.common.error.BusinessException;
import com.hpg.common.error.NotFoundException;
import com.hpg.payment.application.CheckoutService;
import com.hpg.payment.domain.Payment;
import com.hpg.payment.provider.simulated.SimulatedPaymentProvider;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Patient-facing hosted checkout. Identified by the unguessable checkout token, not by login.
 * Errors here are rendered as HTML pages rather than JSON (local exception handlers below).
 */
@Hidden
@RestController
public class CheckoutController {

    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    private final CheckoutService checkout;
    private final CheckoutPages pages;

    public CheckoutController(CheckoutService checkout, CheckoutPages pages) {
        this.checkout = checkout;
        this.pages = pages;
    }

    @GetMapping("/checkout/{token}")
    public ResponseEntity<String> open(@PathVariable String token) {
        return switch (checkout.open(token)) {
            case CheckoutService.Payable p -> pages.payable(p, token);
            case CheckoutService.Finished f -> pages.outcome(f.payment(), f.merchantName(), token);
            case CheckoutService.Unavailable u -> pages.unavailable(u);
        };
    }

    /** Form POST from the provider's checkout handler (field names are Razorpay's). */
    @PostMapping("/checkout/{token}/callback")
    public ResponseEntity<String> callback(@PathVariable String token,
            @RequestParam(name = "razorpay_payment_id", required = false) String paymentId,
            @RequestParam(name = "razorpay_order_id", required = false) String orderId,
            @RequestParam(name = "razorpay_signature", required = false) String signature) {
        return finish(checkout.handleCallback(token, orderId, paymentId, signature), token);
    }

    /** SIMULATED provider only (404 otherwise). */
    @PostMapping("/checkout/{token}/simulate")
    public ResponseEntity<String> simulate(@PathVariable String token,
            @RequestParam SimulatedPaymentProvider.Outcome outcome,
            @RequestParam(defaultValue = "true") boolean deliverCallback) {
        return finish(checkout.simulate(token, outcome, deliverCallback), token);
    }

    /** Back to the hospital's page if it gave us a return URL, else show the outcome here. */
    private ResponseEntity<String> finish(Payment payment, String token) {
        if (payment.getReturnUrl() != null) {
            String target = UriComponentsBuilder.fromUriString(payment.getReturnUrl())
                    .queryParam("paymentId", payment.getId())
                    .queryParam("status", payment.getStatus())
                    .build().toUriString();
            return ResponseEntity.status(HttpStatus.SEE_OTHER).header("Location", target).build();
        }
        return pages.outcome(payment, "Payment", token);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<String> notFound(NotFoundException e) {
        return pages.error(HttpStatus.NOT_FOUND, "This payment link is not valid.");
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<String> rejected(BusinessException e) {
        log.info("Checkout request rejected: {}", e.getMessage());
        return pages.error(HttpStatus.valueOf(e.errorCode().httpStatus()), e.getMessage());
    }
}
