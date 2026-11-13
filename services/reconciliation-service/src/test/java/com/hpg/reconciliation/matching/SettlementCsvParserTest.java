package com.hpg.reconciliation.matching;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementCsvParserTest {

    static final String HEADER = "entity_id,type,order_id,amount,fee,tax,currency,settlement_id,settled_at,created_at\n";

    List<SettlementRow> parse(String csv, int maxRows) throws Exception {
        List<SettlementRow> rows = new ArrayList<>();
        new SettlementCsvParser(maxRows).parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), rows::add);
        return rows;
    }

    @Test
    void readsTheDocumentedFormat() throws Exception {
        List<SettlementRow> rows = parse(HEADER
                + "pay_N1a2b3c4d5e6f7,payment,order_N1a2,20000.00,400.00,72.00,INR,setl_N9,2026-11-13T10:30:00Z,2026-11-12T09:15:02Z\n"
                + "rfnd_N7g8,refund,,5000,0,0,inr,setl_N9,2026-11-13T10:30:00Z,2026-11-12T14:01:44Z\n", 100);
        assertThat(rows).hasSize(2).allMatch(SettlementRow::isValid);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("20000.00");
        assertThat(rows.get(0).fee()).isEqualByComparingTo("400.00");
        assertThat(rows.get(1).entityType()).isEqualTo("refund");
        assertThat(rows.get(1).orderId()).isNull();
        assertThat(rows.get(1).currency()).isEqualTo("INR");
        assertThat(rows.get(1).amount().scale()).isEqualTo(2);
    }

    @Test
    void columnsMayBeInAnyOrderWithQuotesAndAByteOrderMark() throws Exception {
        List<SettlementRow> rows = parse("﻿currency,amount,\"entity_id\",type,note\n"
                + "INR,\"1,000.00\",pay_1,payment,\"a \"\"quoted\"\" note\"\n"
                + "INR,1000.00,pay_2,payment,x\n", 100);
        assertThat(rows.get(0).isValid()).isFalse();           // "1,000.00" is not a plain number
        assertThat(rows.get(1).entityId()).isEqualTo("pay_2");
    }

    @Test
    void badRowsAreRecordedWithTheirNumberAndTheFileContinues() throws Exception {
        List<SettlementRow> rows = parse(HEADER
                + ",payment,,10.00,,,INR,,,\n"
                + "pay_2,payment,,ten,,,INR,,,\n"
                + "pay_3,payment,,10.001,,,INR,,,\n"
                + "pay_4,payment,,10.00,,,RUPEES,,,\n"
                + "pay_5,payment,,10.00,,,INR,,yesterday,\n"
                + "pay_6,payment,,-1.00,,,INR,,,\n"
                + "\n"
                + "pay_7,payment,,10.00,,,INR,,,\n", 100);
        assertThat(rows).extracting(SettlementRow::rowNumber).containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(rows).extracting(SettlementRow::parseError).containsExactly(
                "entity_id is missing", "amount is not a number: ten", "amount has more than 2 decimals: 10.001",
                "currency 'RUPEES' is not an ISO-4217 code", "settled_at is not an ISO-8601 UTC timestamp: yesterday",
                "amount must be positive", null);
    }

    @Test
    void unusableFilesAreRejectedAsAWhole() {
        assertThatThrownBy(() -> parse("", 10)).hasMessageContaining("empty");
        assertThatThrownBy(() -> parse("id,amount\n1,2\n", 10)).hasMessageContaining("Missing column 'entity_id'");
        assertThatThrownBy(() -> parse(HEADER + "pay_1,payment,,1.00,,,INR,,,\npay_2,payment,,1.00,,,INR,,,\n", 1))
                .hasMessageContaining("More than 1 rows");
    }
}
