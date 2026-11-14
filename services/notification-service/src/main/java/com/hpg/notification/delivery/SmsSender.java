package com.hpg.notification.delivery;

import com.hpg.notification.domain.Notification;

/** Port for patient text messages. */
public interface SmsSender {

    DeliveryResult send(Notification notification);
}
