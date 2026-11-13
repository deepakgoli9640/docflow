package com.hpg.console.web;

import com.hpg.console.client.ApiException;
import com.hpg.console.client.GatewayApi;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/** The hospital's API keys: what its own servers use to call the gateway. */
@Controller
public class ApiKeysController {

    private final GatewayApi api;

    public ApiKeysController(GatewayApi api) {
        this.api = api;
    }

    @GetMapping("/api-keys")
    public String list(Model model) {
        model.addAttribute("keys", api.apiKeys());
        return "api-keys";
    }

    @PostMapping("/api-keys")
    public String create(RedirectAttributes attributes) {
        try {
            Map<String, Object> created = api.createApiKey();
            attributes.addFlashAttribute("revealedSecret", created.get("keySecret"));
            attributes.addFlashAttribute("revealedSecretLabel", "Secret for " + created.get("keyId"));
            Flash.success(attributes, "Key created. Copy the secret now — it is not stored and cannot be shown again.");
        } catch (ApiException e) {
            Flash.error(attributes, e);
        }
        return "redirect:/api-keys";
    }

    @PostMapping("/api-keys/{keyId}/revoke")
    public String revoke(@PathVariable String keyId, RedirectAttributes attributes) {
        try {
            api.revokeApiKey(keyId);
            Flash.success(attributes, "Key revoked. Anything still using it will stop working immediately.");
        } catch (ApiException e) {
            // The common one: you cannot revoke the last active key, because you would lock yourself out.
            Flash.error(attributes, e);
        }
        return "redirect:/api-keys";
    }
}
