package com.hpg.refund.api;

import com.hpg.refund.application.RefundWebhookService;
import com.hpg.refund.provider.razorpay.RazorpayRefundProvider;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Razorpay refund webhooks ({@code refund.processed}, {@code refund.failed}). The body is taken as raw
 * bytes: the signature covers the exact bytes sent, so it must be verified before any parsing.
 * Answers 2xx for everything we accepted (including duplicates and events we ignore) so the provider
 * stops retrying; 401 only for a bad signature.
 */
@Hidden
@RestController
public class WebhookController {

    private final RefundWebhookService webhooks;

    public WebhookController(RefundWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/webhooks/razorpay/refunds")
    public ResponseEntity<Void> razorpay(
            @RequestHeader(value = RazorpayRefundProvider.Headers.SIGNATURE, required = false) String signature,
            @RequestHeader(value = RazorpayRefundProvider.Headers.EVENT_ID, required = false) String eventId,
            @RequestBody byte[] body) {
        if (signature == null || eventId == null || eventId.isBlank() || eventId.length() > 100) {
            return ResponseEntity.badRequest().build();
        }
        return switch (webhooks.handle(eventId, signature, body)) {
            case INVALID_SIGNATURE -> ResponseEntity.status(401).build();
            case DUPLICATE, IGNORED, APPLIED -> ResponseEntity.ok().build();
        };
    }
}
