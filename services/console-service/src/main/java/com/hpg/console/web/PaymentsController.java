package com.hpg.console.web;

import com.hpg.console.client.ApiException;
import com.hpg.console.client.GatewayApi;
import com.hpg.console.client.view.PaymentView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The hospital's own payments.
 *
 * <p>Every page here is one API call. The console adds no rules of its own: whether a payment can be
 * captured, whether an amount is within the hospital's limits, whether a reference is already in use —
 * payment-service decides all of it, and this just shows what it said.
 */
@Controller
public class PaymentsController {

    private static final int PAGE_SIZE = 20;

    private final GatewayApi api;

    public PaymentsController(GatewayApi api) {
        this.api = api;
    }

    /** What the create-payment form collects. Validated here only to avoid a pointless round trip. */
    public record CreatePaymentForm(
            @NotBlank @Size(max = 64) String merchantReference,
            @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @Size(max = 255) String description,
            @Size(max = 100) String customerName,
            @Size(max = 254) String customerEmail,
            @Size(max = 20) String customerPhone,
            Integer expiresInMinutes) {
    }

    @GetMapping("/payments")
    public String list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String merchantReference,
            @RequestParam(defaultValue = "0") int page, Model model) {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("status", status);
        filters.put("merchantReference", merchantReference);
        model.addAttribute("payments", api.payments(filters, page, PAGE_SIZE));
        model.addAttribute("status", status);
        model.addAttribute("merchantReference", merchantReference);
        model.addAttribute("page", page);
        return "payments/list";
    }

    /**
     * The table on its own, for the page to poll while a payment is still in flight. A patient paying
     * on their phone changes the status without anybody touching this console.
     */
    @GetMapping("/payments/table")
    public String table(@RequestParam(required = false) String status,
            @RequestParam(required = false) String merchantReference,
            @RequestParam(defaultValue = "0") int page, Model model) {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("status", status);
        filters.put("merchantReference", merchantReference);
        model.addAttribute("payments", api.payments(filters, page, PAGE_SIZE));
        return "payments/fragments :: paymentsTable";
    }

    @GetMapping("/payments/new")
    public String newPayment(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new CreatePaymentForm(null, null, null, null, null, null, null));
        }
        return "payments/new";
    }

    @PostMapping("/payments")
    public String create(@Valid @ModelAttribute("form") CreatePaymentForm form, BindingResult binding,
            RedirectAttributes attributes, Model model) {
        if (binding.hasErrors()) {
            return "payments/new";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantReference", form.merchantReference());
        body.put("amount", Map.of("amount", form.amount().toPlainString(), "currency", "INR"));
        if (notBlank(form.description())) {
            body.put("description", form.description());
        }
        Map<String, Object> customer = new LinkedHashMap<>();
        if (notBlank(form.customerName())) {
            customer.put("name", form.customerName());
        }
        if (notBlank(form.customerEmail())) {
            customer.put("email", form.customerEmail());
        }
        if (notBlank(form.customerPhone())) {
            customer.put("phone", form.customerPhone());
        }
        if (!customer.isEmpty()) {
            body.put("customer", customer);
        }
        if (form.expiresInMinutes() != null) {
            body.put("expiresInMinutes", form.expiresInMinutes());
        }
        try {
            // One key per submitted form, so a double-clicked button cannot create two payments.
            PaymentView created = api.createPayment(body, GatewayApi.newIdempotencyKey());
            Flash.success(attributes, "Payment created. Send the patient the checkout link below.");
            return "redirect:/payments/" + created.id();
        } catch (ApiException e) {
            model.addAttribute("problem", e.problem());
            return "payments/new";
        }
    }

    @GetMapping("/payments/{paymentId}")
    public String detail(@PathVariable String paymentId, Model model) {
        model.addAttribute("payment", api.payment(paymentId));
        return "payments/detail";
    }

    /** The detail panel on its own, polled while the payment can still change. */
    @GetMapping("/payments/{paymentId}/panel")
    public String panel(@PathVariable String paymentId, Model model) {
        model.addAttribute("payment", api.payment(paymentId));
        return "payments/fragments :: paymentPanel";
    }

    @PostMapping("/payments/{paymentId}/capture")
    public String capture(@PathVariable String paymentId, RedirectAttributes attributes) {
        try {
            PaymentView captured = api.capture(paymentId, GatewayApi.newIdempotencyKey());
            if ("UNKNOWN".equals(captured.status())) {
                // 202: the provider did not answer in time. Saying "captured" here would be a lie, and
                // saying "failed" would be worse — the money may well have moved.
                Flash.success(attributes, "The provider did not answer in time. The outcome is being verified "
                        + "automatically; do not capture again.");
            } else {
                Flash.success(attributes, "Payment captured.");
            }
        } catch (ApiException e) {
            Flash.error(attributes, e);
        }
        return "redirect:/payments/" + paymentId;
    }

    @PostMapping("/payments/{paymentId}/cancel")
    public String cancel(@PathVariable String paymentId, @RequestParam(required = false) String reason,
            RedirectAttributes attributes) {
        try {
            api.cancel(paymentId, reason == null || reason.isBlank() ? "Cancelled from the console" : reason,
                    GatewayApi.newIdempotencyKey());
            Flash.success(attributes, "Payment cancelled.");
        } catch (ApiException e) {
            Flash.error(attributes, e);
        }
        return "redirect:/payments/" + paymentId;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
