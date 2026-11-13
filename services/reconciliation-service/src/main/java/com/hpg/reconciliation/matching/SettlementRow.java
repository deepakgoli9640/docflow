package com.hpg.reconciliation.matching;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of a provider settlement report (CSV upload or API), normalised. A row that could not be read
 * has {@code parseError} set and possibly no other fields.
 *
 * @param entityType {@code payment}, {@code refund} or {@code adjustment} (lower case, as the provider writes it)
 */
public record SettlementRow(
        int rowNumber,
        String entityId,
        String entityType,
        String orderId,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal tax,
        String currency,
        String settlementId,
        Instant settledAt,
        Instant createdAt,
        String raw,
        String parseError) {

    public static SettlementRow malformed(int rowNumber, String raw, String error) {
        return new SettlementRow(rowNumber, null, null, null, null, null, null, null, null, null, null, raw, error);
    }

    public boolean isValid() {
        return parseError == null;
    }
}
