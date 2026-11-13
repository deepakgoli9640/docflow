package com.hpg.notification.delivery;

import com.hpg.notification.domain.Channel;
import com.hpg.notification.domain.MerchantNotificationReplica;
import com.hpg.notification.domain.Notification;

/** Sends the patient a short confirmation through whichever {@link SmsSender} is configured. */
public class SmsDeliveryChannel implements DeliveryChannel {

    private final SmsSender smsSender;

    public SmsDeliveryChannel(SmsSender smsSender) {
        this.smsSender = smsSender;
    }

    @Override
    public Channel channel() {
        return Channel.SMS;
    }

    @Override
    public DeliveryResult deliver(Notification notification, MerchantNotificationReplica merchant) {
        String phone = notification.getRecipient();
        if (phone == null || phone.isBlank()) {
            return DeliveryResult.permanent("no phone number", null);
        }
        try {
            smsSender.send(phone, message(notification, merchant));
            return DeliveryResult.delivered(null);
        } catch (RuntimeException e) {
            return DeliveryResult.retryable(e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    private static String message(Notification notification, MerchantNotificationReplica merchant) {
        var amount = notification.getPayload().path("amount");
        String hospital = merchant == null || merchant.getDisplayName() == null ? "the hospital"
                : merchant.getDisplayName();
        String reference = notification.getPayload().path("merchantReference").asText("-");
        if ("PaymentCaptured".equals(notification.getEventType())) {
            return "%s %s received by %s for bill %s. Thank you.".formatted(
                    amount.path("currency").asText("INR"), amount.path("amount").asText("0.00"), hospital, reference);
        }
        return "Update on your payment to %s for bill %s: %s".formatted(hospital, reference,
                notification.getPayload().path("status").asText("updated"));
    }
}
