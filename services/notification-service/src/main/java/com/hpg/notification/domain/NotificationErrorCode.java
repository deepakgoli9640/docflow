package com.hpg.notification.domain;

import com.hpg.common.error.ErrorCode;

public enum NotificationErrorCode implements ErrorCode {

    NOTIFICATION_NOT_FOUND(404, "Notification not found"),
    NOTIFICATION_NOT_REQUEUEABLE(409, "Only a dead notification can be requeued"),
    MERCHANT_CONFIG_UNAVAILABLE(503, "Merchant webhook configuration is unavailable");

    private final int httpStatus;
    private final String title;

    NotificationErrorCode(int httpStatus, String title) {
        this.httpStatus = httpStatus;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String title() {
        return title;
    }
}
