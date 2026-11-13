package com.hpg.console.web;

import com.hpg.console.client.ApiException;
import com.hpg.console.client.GatewayApi;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The hospital's own profile and payment configuration. */
@Controller
public class SettingsController {

    private final GatewayApi api;

    public SettingsController(GatewayApi api) {
        this.api = api;
    }

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("merchant", api.me());
        model.addAttribute("configuration", api.configuration());
        return "settings";
    }

    @PostMapping("/settings/profile")
    public String updateProfile(@RequestParam(required = false) String displayName,
            @RequestParam(required = false) String contactPhone, RedirectAttributes attributes) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (displayName != null && !displayName.isBlank()) {
            body.put("displayName", displayName);
        }
        if (contactPhone != null && !contactPhone.isBlank()) {
            body.put("contactPhone", contactPhone);
        }
        try {
            api.updateProfile(body);
            Flash.success(attributes, "Profile updated. Patients will see the new name on the checkout page.");
        } catch (ApiException e) {
            Flash.error(attributes, e);
        }
        return "redirect:/settings";
    }

    @PostMapping("/settings/configuration")
    public String updateConfiguration(@RequestParam(required = false) List<String> enabledMethods,
            @RequestParam String captureMode, @RequestParam BigDecimal minAmount,
            @RequestParam BigDecimal maxAmountPerTransaction, @RequestParam BigDecimal dailyLimit,
            @RequestParam int paymentTtlMinutes, @RequestParam int maxAttempts,
            @RequestParam int refundWindowDays, @RequestParam(required = false) String webhookUrl,
            @RequestParam(required = false) String notifyPatientEmail,
            @RequestParam(required = false) String notifyPatientSms, RedirectAttributes attributes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabledMethods", enabledMethods == null ? new ArrayList<>() : enabledMethods);
        body.put("captureMode", captureMode);
        body.put("minAmount", money(minAmount));
        body.put("maxAmountPerTransaction", money(maxAmountPerTransaction));
        body.put("dailyLimit", money(dailyLimit));
        body.put("paymentTtlMinutes", paymentTtlMinutes);
        body.put("maxAttempts", maxAttempts);
        body.put("refundWindowDays", refundWindowDays);
        body.put("webhookUrl", webhookUrl == null || webhookUrl.isBlank() ? null : webhookUrl.trim());
        body.put("notifyPatientEmail", notifyPatientEmail != null);
        body.put("notifyPatientSms", notifyPatientSms != null);
        try {
            api.replaceConfiguration(body);
            Flash.success(attributes, "Configuration saved. Other services pick it up within a few seconds.");
        } catch (ApiException e) {
            Flash.error(attributes, e);
        }
        return "redirect:/settings";
    }

    @PostMapping("/settings/webhook-secret/rotate")
    public String rotateWebhookSecret(RedirectAttributes attributes) {
        try {
            Map<String, Object> response = api.rotateWebhookSecret();
            // Shown once, like a password reset: nobody can retrieve it afterwards.
            attributes.addFlashAttribute("revealedSecret", response.get("webhookSecret"));
            attributes.addFlashAttribute("revealedSecretLabel", "New webhook signing secret");
            Flash.success(attributes, "Webhook secret rotated. Copy it now — it cannot be shown again.");
        } catch (ApiException e) {
            Flash.error(attributes, e);
        }
        return "redirect:/settings";
    }

    private static Map<String, String> money(BigDecimal amount) {
        return Map.of("amount", amount.toPlainString(), "currency", "INR");
    }
}
