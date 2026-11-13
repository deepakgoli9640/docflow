package com.hpg.notification.delivery;

import com.hpg.notification.domain.Notification;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Plain-text email over SMTP. */
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mail;
    private final String from;

    public SmtpEmailSender(JavaMailSender mail, String from) {
        this.mail = mail;
        this.from = from;
    }

    @Override
    public DeliveryResult send(Notification n) {
        if (n.getRecipient() == null || n.getRecipient().isBlank()) {
            return DeliveryResult.skip("no recipient address");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(n.getRecipient());
        message.setSubject(n.getSubject());
        message.setText(n.getBody());
        try {
            mail.send(message);
            return DeliveryResult.ok(null);
        } catch (MailException e) {
            return DeliveryResult.retry(null, "SMTP: " + e.getMessage());
        }
    }
}
