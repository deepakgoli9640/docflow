package com.hpg.reconciliation.source;

import com.hpg.reconciliation.matching.SettlementRow;

import java.time.LocalDate;
import java.util.List;

/**
 * The provider's settlement report for a business date, fetched over its API. CSV uploads are parsed into the
 * same {@link SettlementRow} model, so the matching engine does not care where rows came from.
 */
public interface SettlementReportSource {

    String provider();

    List<SettlementRow> fetch(LocalDate businessDate);

    /** The provider could not be reached or answered with an error. */
    class SourceUnavailableException extends RuntimeException {
        public SourceUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
