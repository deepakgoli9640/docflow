package com.hpg.notification.delivery;

import com.hpg.notification.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

/** Delivers a webhook notification: resolve config, guard the URL, sign, POST, judge the response. */
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    private final MerchantDirectory directory;
    private final WebhookUrlGuard guard;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final Clock clock;

    public WebhookSender(MerchantDirectory directory, WebhookUrlGuard guard, Duration connectTimeout,
            Duration requestTimeout, Clock clock) {
        this.directory = directory;
        this.guard = guard;
        this.requestTimeout = requestTimeout;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                // A redirect could point at an internal address the guard never saw.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public DeliveryResult send(Notification n) {
        MerchantDirectory.WebhookConfig config;
        try {
            config = directory.webhookConfig(n.getMerchantId());
        } catch (RuntimeException e) {
            return DeliveryResult.retry(null, "merchant-service lookup failed: " + e.getMessage());
        }
        if (config.webhookUrl() == null) {
            return DeliveryResult.skip("hospital has no webhook URL configured");
        }
        if (config.webhookSecret() == null) {
            return DeliveryResult.retry(null, "hospital has no webhook signing secret");
        }
        URI target;
        try {
            target = guard.check(config.webhookUrl());
        } catch (WebhookUrlGuard.UnsafeWebhookUrlException e) {
            // Retried: the hospital may fix its URL or DNS; the retry budget bounds it.
            return DeliveryResult.retry(null, "blocked webhook URL: " + e.getMessage());
        }
        long timestamp = clock.instant().getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "HPG-Webhooks/1")
                .header(WebhookSigner.EVENT_ID, n.getSourceEventId().toString())
                .header(WebhookSigner.EVENT_TYPE, n.getPublicEventType())
                .header(WebhookSigner.TIMESTAMP, Long.toString(timestamp))
                .header(WebhookSigner.SIGNATURE, WebhookSigner.sign(config.webhookSecret(), timestamp, n.getBody()))
                .POST(HttpRequest.BodyPublishers.ofString(n.getBody(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return DeliveryResult.ok(status);
            }
            return DeliveryResult.retry(status, "hospital endpoint answered HTTP " + status);
        } catch (IOException e) {
            log.debug("Webhook delivery to merchant {} failed: {}", n.getMerchantId(), e.toString());
            return DeliveryResult.retry(null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeliveryResult.retry(null, "interrupted");
        }
    }
}
