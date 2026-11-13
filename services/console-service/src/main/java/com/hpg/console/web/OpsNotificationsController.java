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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Notification delivery, for operations: what was sent, what is still being retried, and what was
 * given up on. This is the page you open when a hospital says "we never got the callback".
 */
@Controller
public class OpsNotificationsController {

    private static final int PAGE_SIZE = 20;

    private final GatewayApi api;

    public OpsNotificationsController(GatewayApi api) {
        this.api = api;
    }

    @GetMapping("/ops/notifications")
    public String list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String merchantId,
            @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("notifications", notifications(status, merchantId, page));
        model.addAttribute("status", status);
        model.addAttribute("merchantId", merchantId);
        model.addAttribute("page", page);
        model.addAttribute("statuses",
                List.of("PENDING", "SENDING", "RETRYING", "DELIVERED", "DEAD", "SKIPPED"));
        return "ops/notifications";
    }

    /** The table alone, so the page can refresh itself while deliveries are being retried. */
    @GetMapping("/ops/notifications/table")
    public String table(@RequestParam(required = false) String status,
            @RequestParam(required = false) String merchantId,
            @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("notifications", notifications(status, merchantId, page));
        return "ops/notification-fragments :: notificationsTable";
    }

    @GetMapping("/ops/notifications/{notificationId}")
    public String detail(@PathVariable String notificationId, Model model) {
        model.addAttribute("details", api.notification(notificationId));
        return "ops/notification-detail";
    }

    @PostMapping("/ops/notifications/{notificationId}/requeue")
    public String requeue(@PathVariable String notificationId, RedirectAttributes attributes) {
        try {
            api.requeueNotification(notificationId);
            Flash.success(attributes, "Queued for delivery again. The earlier attempts are kept.");
        } catch (ApiException e) {
            Flash.error(attributes, e);
        }
        return "redirect:/ops/notifications/" + notificationId;
    }

    private Object notifications(String status, String merchantId, int page) {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("status", status);
        filters.put("merchantId", merchantId);
        return api.notifications(filters, page, PAGE_SIZE);
    }
}
