package com.hpg.payment.api;

import com.hpg.payment.application.WebhookService;
import com.hpg.payment.provider.razorpay.RazorpayPaymentProvider;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Provider webhook endpoint. Public, but every request must carry a valid HMAC signature over the raw
 * body. The body is taken as raw bytes on purpose: parsing and re-serialising JSON would change the bytes
 * and break signature verification.
 *
 * <p>Response codes drive the provider's retry behaviour: 2xx = done (including duplicates and events we
 * deliberately ignore), 401 = bad signature, 5xx = please redeliver.
 */
@Hidden
@RestController
public class WebhookController {

    private final WebhookService webhooks;

    public WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping(value = "/webhooks/razorpay/payments", consumes = "application/json")
    public ResponseEntity<Map<String, String>> razorpay(@RequestBody byte[] body,
            @RequestHeader(value = RazorpayPaymentProvider.Headers.SIGNATURE, required = false) String signature,
            @RequestHeader(value = RazorpayPaymentProvider.Headers.EVENT_ID, required = false) String eventId) {
        WebhookService.Outcome outcome = webhooks.handle(body, signature, eventId);
        return ResponseEntity.ok(Map.of("outcome", outcome.name()));
    }

    @ExceptionHandler(WebhookService.InvalidSignatureException.class)
    public ResponseEntity<Map<String, String>> invalidSignature() {
        return ResponseEntity.status(401).body(Map.of("error", "invalid signature"));
    }
}
