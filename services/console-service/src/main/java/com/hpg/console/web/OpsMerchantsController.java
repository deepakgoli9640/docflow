package com.hpg.console.web;

import com.hpg.console.client.ApiException;
import com.hpg.console.client.GatewayApi;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Set;

/**
 * Merchant onboarding and lifecycle, for operations.
 *
 * <p>Every status change needs a reason, because the API requires one: it goes into the merchant's
 * audit log, and "suspended, no reason given" is not something anyone wants to find six months later.
 */
@Controller
public class OpsMerchantsController {

    private static final int PAGE_SIZE = 20;
    private static final Set<String> ACTIONS = Set.of("activate", "suspend", "deactivate");

    private final GatewayApi api;

    public OpsMerchantsController(GatewayApi api) {
        this.api = api;
    }

    @GetMapping("/ops/merchants")
    public String list(@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            Model model) {
        model.addAttribute("merchants", api.merchants(status, page, PAGE_SIZE));
        model.addAttribute("status", status);
        model.addAttribute("page", page);
        model.addAttribute("statuses", List.of("PENDING_ACTIVATION", "ACTIVE", "SUSPENDED", "DEACTIVATED"));
        return "ops/merchants";
    }

    @GetMapping("/ops/merchants/{merchantId}")
    public String detail(@PathVariable String merchantId, Model model) {
        model.addAttribute("merchant", api.merchant(merchantId));
        return "ops/merchant-detail";
    }

    @PostMapping("/ops/merchants/{merchantId}/{action}")
    public String changeStatus(@PathVariable String merchantId, @PathVariable String action,
            @RequestParam String reason, RedirectAttributes attributes) {
        if (!ACTIONS.contains(action)) {
            Flash.error(attributes, "Unknown action.");
            return "redirect:/ops/merchants/" + merchantId;
        }
        if (reason == null || reason.isBlank()) {
            Flash.error(attributes, "A reason is required: it is written to the merchant's audit log.");
            return "redirect:/ops/merchants/" + merchantId;
        }
        try {
            var merchant = api.changeMerchantStatus(merchantId, action, reason);
            Flash.success(attributes, "Merchant is now " + merchant.status()
                    + ("suspend".equals(action)
                            ? ". Its existing access tokens are refused at the gateway within seconds."
                            : "."));
        } catch (ApiException e) {
            Flash.error(attributes, e);
        }
        return "redirect:/ops/merchants/" + merchantId;
    }
}
