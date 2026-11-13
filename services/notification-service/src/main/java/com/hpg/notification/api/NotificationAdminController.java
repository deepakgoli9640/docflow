package com.hpg.notification.api;

import com.hpg.notification.api.dto.NotificationDetailsResponse;
import com.hpg.notification.api.dto.NotificationResponse;
import com.hpg.notification.application.NotificationAdminService;
import com.hpg.notification.domain.NotificationStatus;
import com.hpg.web.paging.PageResponse;
import com.hpg.web.paging.SortValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/notifications")
@Tag(name = "Notifications (OPS)", description = "Delivery tracking and requeuing of failed notifications")
public class NotificationAdminController {

    private static final Set<String> SORTABLE = Set.of("createdAt", "updatedAt", "status", "attemptCount");

    private final NotificationAdminService admin;

    public NotificationAdminController(NotificationAdminService admin) {
        this.admin = admin;
    }

    @GetMapping
    @Operation(summary = "List notifications", description = "Filter by status or merchant. Sortable by "
            + "createdAt, updatedAt, status, attemptCount.")
    public PageResponse<NotificationResponse> list(@RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) UUID merchantId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        SortValidator.requireAllowed(pageable, SORTABLE);
        return PageResponse.from(admin.list(status, merchantId, pageable), NotificationResponse::from);
    }

    @GetMapping("/{notificationId}")
    @Operation(summary = "Get a notification with every delivery attempt")
    public NotificationDetailsResponse get(@PathVariable UUID notificationId) {
        return NotificationDetailsResponse.from(admin.get(notificationId));
    }

    @PostMapping("/{notificationId}/requeue")
    @Operation(summary = "Requeue a dead notification", description = "Use after the hospital has fixed its "
            + "endpoint. The attempt history is kept.")
    public NotificationResponse requeue(@PathVariable UUID notificationId) {
        return NotificationResponse.from(admin.requeue(notificationId));
    }
}
