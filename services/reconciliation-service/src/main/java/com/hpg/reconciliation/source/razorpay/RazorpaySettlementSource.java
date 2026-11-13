package com.hpg.reconciliation.source.razorpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.hpg.reconciliation.application.ReconProperties;
import com.hpg.reconciliation.matching.SettlementRow;
import com.hpg.reconciliation.source.SettlementReportSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Razorpay settlement recon report: {@code GET /v1/settlements/recon/combined?year=&month=&day=&count=&skip=}.
 * Amounts arrive in paise and timestamps as epoch seconds. Shapes follow Razorpay's public documentation;
 * <b>verify</b> against the sandbox (tested with WireMock only, like the other Razorpay adapters).
 */
public class RazorpaySettlementSource implements SettlementReportSource {

    static final int PAGE = 1000;

    private final RestClient http;

    public RazorpaySettlementSource(ReconProperties.Razorpay properties) {
        if (properties.keyId() == null || properties.keyId().isBlank() || properties.keySecret() == null
                || properties.keySecret().isBlank()) {
            throw new IllegalStateException("Razorpay reconciliation needs RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET "
                    + "(test mode). For local demos use HPG_PAYMENT_PROVIDER=simulated and upload CSV files.");
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(properties.readTimeout());
        this.http = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory)
                .defaultHeaders(h -> h.setBasicAuth(properties.keyId(), properties.keySecret(), StandardCharsets.UTF_8))
                .build();
    }

    @Override
    public String provider() {
        return "RAZORPAY";
    }

    @Override
    public List<SettlementRow> fetch(LocalDate date) {
        List<SettlementRow> rows = new ArrayList<>();
        int skip = 0;
        while (true) {
            JsonNode page;
            try {
                int s = skip;
                page = http.get().uri(u -> u.path("/v1/settlements/recon/combined")
                                .queryParam("year", date.getYear()).queryParam("month", date.getMonthValue())
                                .queryParam("day", date.getDayOfMonth()).queryParam("count", PAGE).queryParam("skip", s).build())
                        .retrieve().body(JsonNode.class);
            } catch (RestClientException e) {
                throw new SourceUnavailableException("Razorpay settlement report for " + date + " failed: " + e.getMessage(), e);
            }
            JsonNode items = page == null ? null : page.path("items");
            if (items == null || !items.isArray() || items.isEmpty()) {
                return rows;
            }
            for (JsonNode item : items) {
                rows.add(toRow(rows.size() + 1, item));
            }
            if (items.size() < PAGE) {
                return rows;
            }
            skip += PAGE;
        }
    }

    static SettlementRow toRow(int rowNumber, JsonNode i) {
        String raw = i.toString();
        try {
            String entityId = text(i, "entity_id");
            String type = text(i, "type");
            if (entityId == null || type == null || !i.hasNonNull("amount")) {
                return SettlementRow.malformed(rowNumber, raw, "entity_id, type or amount missing");
            }
            String currency = text(i, "currency") == null ? "INR" : text(i, "currency").toUpperCase(Locale.ROOT);
            return new SettlementRow(rowNumber, entityId, type.toLowerCase(Locale.ROOT), text(i, "order_id"), paise(i, "amount"),
                    paise(i, "fee"), paise(i, "tax"), currency, text(i, "settlement_id"), epoch(i, "settled_at"),
                    epoch(i, "created_at"), raw, null);
        } catch (RuntimeException e) {
            return SettlementRow.malformed(rowNumber, raw, e.getMessage());
        }
    }

    private static BigDecimal paise(JsonNode i, String field) {
        return i.hasNonNull(field) ? BigDecimal.valueOf(i.get(field).asLong()).movePointLeft(2) : null;
    }

    private static Instant epoch(JsonNode i, String field) {
        return i.hasNonNull(field) && i.get(field).asLong() > 0 ? Instant.ofEpochSecond(i.get(field).asLong()) : null;
    }

    private static String text(JsonNode i, String field) {
        return i.hasNonNull(field) && !i.get(field).asText().isBlank() ? i.get(field).asText() : null;
    }
}
