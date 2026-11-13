package com.hpg.notification.delivery;

import com.hpg.notification.domain.Notification;

/** Port for patient emails (SMTP locally via Mailpit; any provider in production). */
public interface EmailSender {

    DeliveryResult send(Notification notification);
}
