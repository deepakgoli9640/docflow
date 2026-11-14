package com.hpg.notification.delivery;

import com.hpg.notification.application.SmsProperties;
import com.hpg.notification.domain.Notification;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * MSG91 Flow API ({@code POST /api/v5/flow}), the common route for transactional SMS in India: the text is a
 * DLT-registered template on MSG91's side; we send the template id and the variables (amount, bill reference).
 * Verify field names against MSG91's documentation before going live: template_id, recipients[].mobiles,
 * variables per template, header {@code authkey}.
 */
public class Msg91SmsSender implements SmsSender {

    private final RestClient http;
    private final SmsProperties.Msg91 config;

    public Msg91SmsSender(SmsProperties.Msg91 config, Duration connectTimeout, Duration requestTimeout) {
        this.config = config;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(connectTimeout).version(HttpClient.Version.HTTP_1_1).build());
        factory.setReadTimeout(requestTimeout);
        this.http = RestClient.builder().baseUrl(config.baseUrl()).requestFactory(factory)
                .defaultHeader("authkey", config.authKey() == null ? "" : config.authKey()).build();
    }

    @Override
    public DeliveryResult send(Notification n) {
        if (n.getRecipient() == null || n.getRecipient().isBlank()) {
            return DeliveryResult.skip("no phone number");
        }
        String template = config.templates().get(n.getPublicEventType());
        if (template == null) {
            return DeliveryResult.skip("no DLT template configured for " + n.getPublicEventType());
        }
        Map<String, Object> recipient = new java.util.LinkedHashMap<>();
        recipient.put("mobiles", n.getRecipient().replaceAll("[^0-9]", ""));   // country code + number, digits only
        recipient.put("message", n.getBody());                                  // also available as a variable
        Map<String, Object> body = Map.of("template_id", template, "short_url", "0", "recipients", List.of(recipient));
        try {
            http.post().uri("/api/v5/flow").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
            return DeliveryResult.ok(200);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            // 4xx other than throttling: our request or configuration is wrong; retrying will not help.
            return status >= 400 && status < 500 && status != 429
                    ? DeliveryResult.skip("MSG91 rejected the message: HTTP " + status)
                    : DeliveryResult.retry(status, "MSG91: HTTP " + status);
        } catch (ResourceAccessException e) {
            return DeliveryResult.retry(null, "MSG91 unreachable: " + e.getMessage());
        }
    }
}
