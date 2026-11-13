# Sample settlement reports

Format examples for `POST /api/v1/reconciliation/settlement-files` (docs/05-reconciliation.md §3), modelled
on Razorpay's settlement recon report. The ids are placeholders: against a running stack they show up as
"missing in ledger". For a report built from your own local data, run `./scripts/seed-demo-data.sh` (it
uploads one with deliberate differences) or see `scripts/smoke-test-phase6.sh`.

| File | Shows |
|---|---|
| `clean.csv` | Two payments and a refund (all would match a ledger that booked them) |
| `discrepancies.csv` | Short settlement (`19990.00` vs `20000.00`), the same payment twice, an unknown payment, an `adjustment` row that needs review, and an unreadable amount |

```bash
curl -X POST "http://localhost:8080/api/v1/reconciliation/settlement-files?businessDate=2026-11-13" \
  -H "Authorization: Bearer $OPS_TOKEN" -F "file=@samples/settlement/discrepancies.csv;type=text/csv"
```
