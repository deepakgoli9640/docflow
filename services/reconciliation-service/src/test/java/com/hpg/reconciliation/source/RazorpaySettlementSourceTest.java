package com.hpg.reconciliation.source;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.hpg.reconciliation.application.ReconProperties;
import com.hpg.reconciliation.matching.SettlementRow;
import com.hpg.reconciliation.source.razorpay.RazorpaySettlementSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against WireMock stubs of Razorpay's documented settlement recon API (a SIMULATION of Razorpay). */
class RazorpaySettlementSourceTest {

    WireMockServer server;
    RazorpaySettlementSource source;

    @BeforeEach
    void start() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        source = new RazorpaySettlementSource(new ReconProperties.Razorpay(server.baseUrl(), "rzp_test_x", "secret",
                Duration.ofMillis(500), Duration.ofSeconds(1)));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void mapsPaiseAndEpochSecondsAndReportsUnreadableItems() {
        server.stubFor(get(urlPathEqualTo("/v1/settlements/recon/combined")).withQueryParam("year", equalTo("2026"))
                .withQueryParam("month", equalTo("11")).withQueryParam("day", equalTo("12")).withQueryParam("skip", equalTo("0"))
                .willReturn(okJson("""
                        {"entity":"collection","count":3,"items":[
                          {"entity_id":"pay_1","type":"payment","amount":2000000,"fee":40000,"tax":7200,"currency":"INR",
                           "settlement_id":"setl_1","order_id":"order_1","settled_at":1794474904,"created_at":1794388504},
                          {"entity_id":"rfnd_1","type":"refund","amount":500000,"fee":0,"tax":0,"currency":"INR",
                           "settlement_id":"setl_1","settled_at":1794474904,"created_at":1794400000},
                          {"type":"adjustment","amount":100}]}""")));

        List<SettlementRow> rows = source.fetch(LocalDate.of(2026, 11, 12));

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("20000.00");
        assertThat(rows.get(0).fee()).isEqualByComparingTo("400.00");
        assertThat(rows.get(0).settledAt()).isEqualTo(Instant.ofEpochSecond(1794474904));
        assertThat(rows.get(1).entityType()).isEqualTo("refund");
        assertThat(rows.get(2).isValid()).isFalse();
    }

    @Test
    void failuresAreReportedAsSourceUnavailable() {
        server.stubFor(get(urlPathEqualTo("/v1/settlements/recon/combined")).willReturn(aResponse().withStatus(502)));
        assertThatThrownBy(() -> source.fetch(LocalDate.of(2026, 11, 12)))
                .isInstanceOf(SettlementReportSource.SourceUnavailableException.class);
    }
}
