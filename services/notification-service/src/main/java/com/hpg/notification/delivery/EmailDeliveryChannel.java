package com.hpg.notification.delivery;

import com.hpg.notification.domain.Channel;
import com.hpg.notification.domain.MerchantNotificationReplica;
import com.hpg.notification.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Sends the patient a receipt or a failure notice over SMTP. Locally that is Mailpit, which speaks real
 * SMTP and shows the messages in a web inbox — a real protocol against a fake mailbox, rather than a
 * stub pretending mail was sent.
 *
 * <p>Plain text on purpose: a payment receipt needs no HTML, and plain text cannot carry a tracking
 * pixel or a script into a patient's inbox.
 */
public class EmailDeliveryChannel implements DeliveryChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailDeliveryChannel.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public EmailDeliveryChannel(JavaMailSender mailSender, String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public DeliveryResult deliver(Notification notification, MerchantNotificationReplica merchant) {
        String recipient = notification.getRecipient();
        if (recipient == null || recipient.isBlank()) {
            return DeliveryResult.permanent("no email address", null);
        }
        String hospital = merchant == null || merchant.getDisplayName() == null ? "the hospital"
                : merchant.getDisplayName();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(recipient);
        message.setSubject(subject(notification, hospital));
        message.setText(text(notification, hospital));
        try {
            mailSender.send(message);
            return DeliveryResult.delivered(null);
        } catch (MailAuthenticationException e) {
            // Our own SMTP credentials are wrong: retrying every message would not fix it, but the
            // notification must not be dropped silently either, so it is dead-lettered and alerted on.
            log.error("SMTP authentication failed; check the mail configuration", e);
            return DeliveryResult.permanent("SMTP authentication failed", null);
        } catch (MailSendException e) {
            return DeliveryResult.retryable("SMTP send failed: " + rootMessage(e), null);
        } catch (RuntimeException e) {
            return DeliveryResult.retryable(e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    private static String subject(Notification notification, String hospital) {
        String amount = amount(notification);
        return switch (notification.getEventType()) {
            case "PaymentCaptured" -> "Payment received - " + hospital + " (" + amount + ")";
            case "PaymentFailed" -> "Payment could not be completed - " + hospital;
            case "PaymentExpired" -> "Your payment link has expired - " + hospital;
            case "PaymentCancelled" -> "Payment request cancelled - " + hospital;
            default -> "Payment update - " + hospital;
        };
    }

    private static String text(Notification notification, String hospital) {
        String reference = notification.getPayload().path("merchantReference").asText("-");
        String amount = amount(notification);
        String body = switch (notification.getEventType()) {
            case "PaymentCaptured" -> """
                    We have received your payment of %s for %s.

                    Reference: %s
                    Payment id: %s

                    Keep this email as your receipt.""".formatted(amount, hospital, reference, notification.getPaymentId());
            case "PaymentFailed" -> """
                    Your payment of %s for %s could not be completed.

                    Reference: %s

                    No money has been taken. Please contact %s for a new payment link."""
                    .formatted(amount, hospital, reference, hospital);
            case "PaymentExpired" -> """
                    The payment link for your %s bill (reference %s) has expired.

                    No money has been taken. Please contact %s if you still need to pay."""
                    .formatted(hospital, reference, hospital);
            default -> """
                    There is an update on your payment for %s (reference %s): %s."""
                    .formatted(hospital, reference, notification.getPayload().path("status").asText("updated"));
        };
        return body + "\n\nThis message was sent on behalf of " + hospital + ". Please do not reply.\n";
    }

    private static String amount(Notification notification) {
        var amount = notification.getPayload().path("amount");
        return amount.path("currency").asText("INR") + " " + amount.path("amount").asText("0.00");
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage();
    }
}
