-- Phase 9c: settlement postings (reconciliation) and chargebacks (disputes).
ALTER TABLE ledger_entries DROP CONSTRAINT ck_ledger_posting_type;
ALTER TABLE ledger_entries ADD CONSTRAINT ck_ledger_posting_type
    CHECK (posting_type IN ('CAPTURE', 'REFUND', 'REVERSAL', 'SETTLEMENT', 'CHARGEBACK', 'CHARGEBACK_REVERSAL'));
