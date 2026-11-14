package com.hpg.notification.delivery;

import com.hpg.common.masking.LogMasking;
import com.hpg.notification.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Local development: "sends" by logging the text with the number masked. Nothing leaves the machine. */
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public DeliveryResult send(Notification n) {
        if (n.getRecipient() == null || n.getRecipient().isBlank()) {
            return DeliveryResult.skip("no phone number");
        }
        log.info("SMS (not sent: provider=log) to {}: {}", LogMasking.mask(n.getRecipient()), n.getBody());
        return DeliveryResult.ok(null);
    }
}
