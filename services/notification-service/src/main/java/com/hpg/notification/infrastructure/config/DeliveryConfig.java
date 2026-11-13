package com.hpg.notification.infrastructure.config;

import com.hpg.notification.application.Backoff;
import com.hpg.notification.application.NotificationProperties;
import com.hpg.notification.delivery.EmailSender;
import com.hpg.notification.delivery.MerchantDirectory;
import com.hpg.notification.delivery.MerchantServiceDirectory;
import com.hpg.notification.delivery.SmtpEmailSender;
import com.hpg.notification.delivery.WebhookSender;
import com.hpg.notification.delivery.WebhookUrlGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import java.security.SecureRandom;
import java.time.Clock;

@Configuration
public class DeliveryConfig {

    private static final Logger log = LoggerFactory.getLogger(DeliveryConfig.class);

    @Bean
    @ConditionalOnMissingBean
    public MerchantDirectory merchantDirectory(NotificationProperties properties, Clock clock) {
        return new MerchantServiceDirectory(properties, clock);
    }

    @Bean
    public WebhookUrlGuard webhookUrlGuard(NotificationProperties properties) {
        if (properties.allowInsecureWebhooks()) {
            log.warn("*** Insecure webhook URLs (http, private addresses) are ALLOWED. Local development only. ***");
        }
        return new WebhookUrlGuard(properties.allowInsecureWebhooks());
    }

    @Bean
    public WebhookSender webhookSender(MerchantDirectory directory, WebhookUrlGuard guard,
            NotificationProperties properties, Clock clock) {
        return new WebhookSender(directory, guard, properties.connectTimeout(), properties.requestTimeout(), clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public EmailSender emailSender(JavaMailSender mail, NotificationProperties properties) {
        return new SmtpEmailSender(mail, properties.emailFrom());
    }

    @Bean
    @ConditionalOnMissingBean
    public com.hpg.notification.delivery.SmsSender smsSender(com.hpg.notification.application.SmsProperties sms,
            NotificationProperties properties) {
        if ("msg91".equalsIgnoreCase(sms.provider())) {
            return new com.hpg.notification.delivery.Msg91SmsSender(sms.msg91(), properties.connectTimeout(),
                    properties.requestTimeout());
        }
        log.info("SMS provider 'log': patient text messages are logged (number masked), not sent");
        return new com.hpg.notification.delivery.LoggingSmsSender();
    }

    @Bean
    public Backoff notificationBackoff(NotificationProperties properties) {
        SecureRandom random = new SecureRandom();
        return new Backoff(properties.dispatch().initialBackoff(), properties.dispatch().maxBackoff(), random::nextDouble);
    }
}
