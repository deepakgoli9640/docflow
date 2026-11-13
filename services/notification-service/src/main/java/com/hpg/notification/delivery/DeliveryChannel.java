package com.hpg.notification.delivery;

import com.hpg.notification.domain.Channel;
import com.hpg.notification.domain.MerchantNotificationReplica;
import com.hpg.notification.domain.Notification;

/**
 * One way of delivering a notification. Implementations do the I/O and classify the result; they never
 * decide when to retry — that is the dispatcher's job, and keeping it there means all channels share one
 * retry policy and one audit trail.
 */
public interface DeliveryChannel {

    Channel channel();

    DeliveryResult deliver(Notification notification, MerchantNotificationReplica merchant);
}
