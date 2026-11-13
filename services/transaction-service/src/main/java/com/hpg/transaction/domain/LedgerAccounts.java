package com.hpg.transaction.domain;

import java.util.UUID;
import java.util.regex.Pattern;

/** Chart of accounts (docs/04-ledger.md §2.1). */
public final class LedgerAccounts {

    /** Asset: money collected and held by the provider, not yet settled to our bank. */
    public static final String PROVIDER_CLEARING = "PROVIDER_CLEARING";
    /** Liability prefix: what we owe each hospital. */
    public static final String MERCHANT_PAYABLE_PREFIX = "MERCHANT_PAYABLE:";

    private static final Pattern VALID = Pattern.compile("PROVIDER_CLEARING|SETTLEMENT_BANK|PROVIDER_FEES"
            + "|MERCHANT_PAYABLE:[0-9a-f-]{36}");

    private LedgerAccounts() {
    }

    public static String merchantPayable(UUID merchantId) {
        return MERCHANT_PAYABLE_PREFIX + merchantId;
    }

    public static boolean isValid(String code) {
        return code != null && VALID.matcher(code).matches();
    }

    /** Liabilities are credit-normal; assets and expenses debit-normal. */
    public static boolean isCreditNormal(String code) {
        return code.startsWith(MERCHANT_PAYABLE_PREFIX);
    }
}
