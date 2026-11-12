package com.hpg.payment.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.crypto.SecureTokens;
import com.hpg.payment.application.CheckoutService;
import com.hpg.payment.domain.Payment;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Renders the patient-facing pages. Plain server-side HTML: no template engine dependency for four
 * small pages. Every dynamic value is HTML-escaped; the provider options are embedded as JSON with
 * {@code <} escaped so a value can never close the script tag.
 *
 * <p>Content-Security-Policy with a per-response nonce: only our inline script (carrying the nonce) and
 * the provider's checkout script may run. The provider domains listed are Razorpay's documented ones
 * (verify against the sandbox).
 */
@Component
public class CheckoutPages {

    private static final String RAZORPAY_CSP_SOURCES = "https://checkout.razorpay.com https://api.razorpay.com";
    private final ObjectMapper json;

    public CheckoutPages(ObjectMapper json) {
        this.json = json;
    }

    public ResponseEntity<String> payable(CheckoutService.Payable view, String token) {
        String nonce = SecureTokens.urlSafe(16);
        Payment p = view.payment();
        String body = view.config().simulated() ? simulatedBody(token) : providerBody(view, nonce, token);
        return page(HttpStatus.OK, nonce, "Pay " + view.merchantName(), header(view.merchantName(), p) + body,
                !view.config().simulated());
    }

    public ResponseEntity<String> outcome(Payment payment, String merchantName, String token) {
        String message = switch (payment.getStatus()) {
            case CAPTURED -> "Payment successful. Thank you — a receipt will be sent to you.";
            case AUTHORIZED -> "Payment authorised. The hospital will confirm and complete it.";
            case PROCESSING, UNKNOWN -> "We are confirming your payment with the bank. You will receive a "
                    + "confirmation shortly; please do not pay again.";
            case FAILED -> "This payment could not be completed. Please contact the hospital for a new payment link.";
            case CANCELLED -> "This payment request was cancelled by the hospital.";
            case EXPIRED -> "This payment link has expired. Please contact the hospital for a new link.";
            case CREATED, PENDING -> payment.isPayableAt(java.time.Instant.now())
                    ? "Your payment was not completed. <a href=\"" + esc(token) + "\">Try again</a>."
                    : "This payment link has expired.";
        };
        String nonce = SecureTokens.urlSafe(16);
        return page(HttpStatus.OK, nonce, merchantName, header(merchantName, payment)
                + "<p class=\"status s-" + payment.getStatus().name().toLowerCase() + "\">" + message + "</p>", false);
    }

    public ResponseEntity<String> unavailable(CheckoutService.Unavailable view) {
        return page(HttpStatus.SERVICE_UNAVAILABLE, SecureTokens.urlSafe(16), view.merchantName(),
                header(view.merchantName(), view.payment())
                        + "<p class=\"status\">Payments are temporarily unavailable. Please try again in a few minutes."
                        + "</p>", false);
    }

    public ResponseEntity<String> error(HttpStatus status, String message) {
        return page(status, SecureTokens.urlSafe(16), "Payment", "<h1>Payment</h1><p class=\"status\">" + esc(message)
                + "</p>", false);
    }

    private String providerBody(CheckoutService.Payable view, String nonce, String token) {
        String options;
        try {
            options = json.writeValueAsString(view.config().options()).replace("<", "\\u003c");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return """
                <button id="pay">Pay now</button>
                <p id="msg" class="note"></p>
                <form id="cb" method="post" action="%s/callback">
                  <input type="hidden" name="razorpay_payment_id"><input type="hidden" name="razorpay_order_id">
                  <input type="hidden" name="razorpay_signature">
                </form>
                <script src="%s"></script>
                <script nonce="%s">
                  var options = %s;
                  options.handler = function (r) {
                    var f = document.getElementById('cb');
                    f.razorpay_payment_id.value = r.razorpay_payment_id;
                    f.razorpay_order_id.value = r.razorpay_order_id;
                    f.razorpay_signature.value = r.razorpay_signature;
                    f.submit();
                  };
                  var rzp = new Razorpay(options);
                  rzp.on('payment.failed', function () {
                    document.getElementById('msg').textContent = 'The payment did not go through. You can try again.';
                  });
                  document.getElementById('pay').onclick = function () { rzp.open(); };
                </script>
                """.formatted(esc(token), esc(view.config().scriptUrl()), nonce, options);
    }

    private String simulatedBody(String token) {
        String t = esc(token);
        return """
                <p class="sim">SIMULATED PROVIDER — local development only. No money moves.</p>
                <form method="post" action="%1$s/simulate"><input type="hidden" name="outcome" value="SUCCESS">
                  <input type="hidden" name="deliverCallback" value="true"><button>Pay (simulated success)</button></form>
                <form method="post" action="%1$s/simulate"><input type="hidden" name="outcome" value="FAILURE">
                  <input type="hidden" name="deliverCallback" value="true"><button class="secondary">Fail this attempt</button></form>
                <form method="post" action="%1$s/simulate"><input type="hidden" name="outcome" value="SUCCESS">
                  <input type="hidden" name="deliverCallback" value="false">
                  <button class="secondary">Pay, but lose the callback (recovery demo)</button></form>
                """.formatted(t);
    }

    private static String header(String merchantName, Payment p) {
        return "<h1>" + esc(merchantName) + "</h1><p class=\"amount\">" + esc(p.amount().currency()) + " "
                + esc(p.amount().amount().toPlainString()) + "</p>"
                + (p.getDescription() == null ? "" : "<p class=\"note\">" + esc(p.getDescription()) + "</p>")
                + "<p class=\"note\">Reference: " + esc(p.getMerchantReference()) + "</p>";
    }

    private ResponseEntity<String> page(HttpStatus status, String nonce, String title, String content,
            boolean loadsProvider) {
        String providers = loadsProvider ? " " + RAZORPAY_CSP_SOURCES : "";
        String csp = "default-src 'none'; script-src 'nonce-" + nonce + "'" + providers + "; style-src 'nonce-" + nonce
                + "'; frame-src" + (loadsProvider ? providers : " 'none'") + "; connect-src" + (loadsProvider ? providers
                : " 'none'") + "; img-src 'self' data: https:; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";
        String html = """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="referrer" content="no-referrer">
                <title>%s</title>
                <style nonce="%s">
                  body{font-family:system-ui,sans-serif;max-width:28rem;margin:3rem auto;padding:0 1rem;color:#1d2433}
                  h1{font-size:1.3rem}.amount{font-size:2rem;font-weight:600;margin:.5rem 0}
                  .note{color:#5b6475;font-size:.9rem}.status{padding:1rem;border-radius:.5rem;background:#f1f4f9}
                  .s-captured{background:#e6f6ec}.s-failed,.s-expired,.s-cancelled{background:#fdecec}
                  button{width:100%%;padding:.8rem;margin:.4rem 0;font-size:1rem;border:0;border-radius:.5rem;
                         background:#2453d6;color:#fff;cursor:pointer}
                  button.secondary{background:#e3e8f2;color:#1d2433}
                  .sim{background:#fff4d6;padding:.6rem;border-radius:.5rem;font-weight:600}
                </style></head><body>%s</body></html>
                """.formatted(esc(title), nonce, content);
        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", csp)
                .header("X-Content-Type-Options", "nosniff")
                .body(html);
    }

    private static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}
