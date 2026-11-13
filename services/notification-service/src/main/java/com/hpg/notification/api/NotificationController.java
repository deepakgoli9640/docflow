package com.hpg.notification.api;

import com.hpg.notification.api.dto.NotificationResponse;
import com.hpg.notification.application.NotificationQueryService;
import com.hpg.notification.domain.Channel;
import com.hpg.notification.domain.NotificationStatus;
import com.hpg.web.paging.PageResponse;
import com.hpg.web.paging.SortValidator;
import com.hpg.web.security.MerchantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@Tag(name = "Notifications", description = "Delivery status of webhooks to the hospital and emails to patients")
public class NotificationController {

    private static final Set<String> SORTABLE = Set.of("createdAt", "updatedAt", "status");

    private final NotificationQueryService notifications;

    public NotificationController(NotificationQueryService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/api/v1/notifications")
    @Operation(summary = "List notifications", description = "Filters: paymentId, status, channel.")
    public PageResponse<NotificationResponse> list(MerchantPrincipal principal,
            @RequestParam(required = false) UUID paymentId,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) Channel channel,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        SortValidator.requireAllowed(pageable, SORTABLE);
        return PageResponse.from(notifications.list(principal.merchantId(), paymentId, status, channel, pageable),
                NotificationResponse::summary);
    }

    @GetMapping("/api/v1/notifications/{id}")
    @Operation(summary = "One notification with its delivery attempts")
    public NotificationResponse get(MerchantPrincipal principal, @PathVariable UUID id) {
        var d = notifications.get(principal.merchantId(), id);
        return NotificationResponse.of(d.notification(), d.attempts());
    }

    @PostMapping("/api/v1/admin/notifications/{id}/retry")
    @Operation(summary = "OPS: re-queue a DEAD notification")
    public NotificationResponse retry(@PathVariable UUID id) {
        return NotificationResponse.summary(notifications.requeue(id));
    }
}
