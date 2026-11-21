# Postman workflow guide: learn every feature by sending requests

This guide walks through the Postman collection that `import-postman.bat` loads, **request by request, in
order**. For each step it says what the request does in business terms, what happens behind the scenes, and what
to look at, so you learn how the payment gateway works by using it.

Allow about 2 hours to go through it slowly. The whole collection also runs in about a minute with **Run**, but
sending requests one at a time and reading the responses is how you learn.

> Prefer it visual? Open [postman-walkthrough.html](postman-walkthrough.html) in a browser: the same walkthrough
> with a progress tick-box per request.
>
> Related: [testing-guide.md](testing-guide.md) (full test plan with curl, failure drills, observability) and
> the README's architecture section.

---

## Before you start

### 1. Start everything
1. Start **Docker Desktop**.
2. Double-click **`start-hpg.bat`** (first run: 10–20 minutes). Wait until it opens the dashboard.
3. Double-click **`import-postman.bat`** and import into Postman (API key or drag-and-drop).
4. In Postman, choose the **HPG local** environment (top right).

### 2. Open these side by side
| Window | Why |
|---|---|
| Postman | Send the requests |
| http://localhost:8025 (Mailpit) | The emails the patient receives |
| http://localhost:8090 (dashboard) | See the same data the way a hospital or operations person sees it |
| Postman **Console** (bottom left) | Tokens being fetched, and the `errorCode` + `correlationId` of every error |

### 3. Optional: see the webhooks the hospital receives
The collection sets the hospital's webhook URL to `http://hook-receiver:8080/hpg/webhooks`. Start a small
receiver that prints every call (in a terminal):
```bat
docker run -d --rm --name hook-receiver --network hpg_default mendhak/http-https-echo:31
docker logs -f hook-receiver
```
Without it, webhooks simply fail and are retried; everything else works.

### 4. What the collection does for you
- **Tokens.** You never call `/oauth2/token` yourself (except to learn how, in folder 0). A script fetches an
  **operations token** and a **hospital token** when a request needs one and refreshes it before it expires.
- **Idempotency keys.** Money-moving requests send a fresh `Idempotency-Key`, or a saved one when the point is
  to show a retry.
- **Ids carry over.** Each response's id (payment, refund, dispute, batch…) is saved into a collection variable
  (*Collection → Variables*) and used by the next requests. So **send requests in order**.
- **Tests.** Each request checks the result. The *Test Results* tab shows pass/fail.

### 5. Two kinds of caller
| Who | Token | Can do |
|---|---|---|
| **Hospital** (merchant) | `hospitalToken`, from its API key | Its own payments, refunds, disputes, transactions, settings. Never another hospital's data |
| **Operations** (platform staff) | `opsToken`, from the `ops-console` client | Activate/suspend hospitals, ledger, reconciliation, failed notifications, dead letters |

---

## 0. Platform: how sign-in works

| # | Request | What you learn |
|---|---|---|
| 0.1 | **Get an operations token** | OAuth2 *client credentials*: the client id + secret go in HTTP Basic auth, the answer is a JWT `access_token` valid 15 minutes (`expires_in: 900`). Every API call then sends `Authorization: Bearer <token>`. |
| 0.2 | **Public signing keys (JWKS)** | The public keys anyone can use to verify our tokens. During a key rotation two keys are listed, so tokens signed with the old key keep working. |

**Try:** paste the `access_token` into https://jwt.io. You see `roles: ["OPS"]`; a hospital token also carries
`mid` (the hospital's id). **Every service takes the hospital from this claim, never from the request**, which
is how hospitals are kept apart.

---

## A. Hospital onboarding (merchant-service)

The business story: a hospital signs up, the platform checks it (KYC), switches it on, and the hospital
configures how it wants to be paid.

| # | Request | What happens and what to look at |
|---|---|---|
| A1 | **Register a hospital** *(public)* | Creates the hospital in `PENDING_ACTIVATION`. The response holds the first **API key** (`keyId` + `keySecret`) and the **webhook secret**, **shown only once** (stored hashed). The collection saves the key as `hospitalKeyId/Secret`; from now on requests run as this hospital. |
| A2 | **Activate the hospital** *(OPS)* | Status → `ACTIVE`, with an audited reason. Before this, payments are refused with `403 MERCHANT_NOT_ACTIVE`. Behind the scenes a **merchant snapshot** is published to Kafka, so payment-service, refund-service and the gateway learn the new status within seconds. |
| A3 | **My profile** | The hospital reads itself. Note: no id in the URL (`/me`): the id comes from the token. |
| A4 | **Update my profile** | Only `displayName` and `contactPhone` can change; the legal name and status are operations' business. |
| A5 | **My configuration** | The defaults every new hospital gets. |
| A6 | **Set my configuration** | Read each field: `captureMode` (AUTO = money taken when the patient pays; MANUAL = hospital confirms later), `minAmount` / `maxAmountPerTransaction` / `dailyLimit`, `paymentTtlMinutes` (how long a link works), `maxAttempts` (declines allowed), `refundWindowDays`, `webhookUrl`, `notifyPatientEmail/Sms`. |
| A7 | **Create a second API key** | Up to **2 active keys**: that is how a key is replaced without downtime (create new → switch the app → revoke old). |
| A8 | **List my API keys** | No secrets in the list, ever. |
| A9 | **Revoke the second API key** | `204`. Revoking the *last* active key is refused (`409 LAST_ACTIVE_API_KEY`), so a hospital cannot lock itself out. |
| A10 | **Rotate my webhook signing secret** | New secret, shown once. The hospital uses it to verify `X-HPG-Signature` on webhooks. |

**Look at:** dashboard → sign in as operations → *Hospitals*: the new hospital is there, `ACTIVE`.

---

## B. Payments and checkout (payment-service)

The business story: the hospital bills the patient's share, the patient pays from a link on their phone, the
hospital sees the money arrive.

| # | Request | What happens and what to look at |
|---|---|---|
| B1 | **Create a payment** | Status `PENDING`, with a `checkoutUrl`. Because `sendCheckoutLink` defaults to true, **Mailpit now has "Payment request from …"** with the link, and an SMS is logged (number masked). `merchantReference` is the hospital's bill number. |
| B2 | **Same request again (idempotent replay)** | Same key + same body → the **same payment** comes back with header `Idempotent-Replayed: true`. This is what makes a network retry safe: no double payment. |
| B3 | **Same key, different body** | `422 IDEMPOTENCY_KEY_REUSED`: a key belongs to one request only. |
| B4 | **Same bill, new key** | `409 DUPLICATE_PAYMENT`: one *live* payment per bill number, even with a different key. |
| B5 | **Checkout page** | The page the patient sees. **Open the `checkoutUrl` in a browser too**: three buttons for the simulated provider (pay, fail, pay-but-lose-the-callback). |
| B6 | **Patient pays (simulated provider)** | Same as clicking *Pay*. The provider confirms, and with AUTO capture the payment is captured. |
| B7 | **Get the payment** | `CAPTURED`, with the list of `attempts` and the provider's payment id. Mailpit: **"Payment received"** receipt. Hook receiver: `payment.captured`. |
| B8 | **List payments** | Filters: `status`, `merchantReference`, `from`/`to`, paging. Enable the disabled query params in Postman to try them. |
| B9 | **Payment declined** | A second payment, for the decline demo. |
| B10 | **Patient's bank declines** | The payment stays `PENDING` so the patient can try again, and becomes `FAILED` after `maxAttempts` (3) declines. |
| B11 | **Checkout callback** | Where the real provider sends the patient back. A wrong signature is refused: shown so you know the endpoint exists. |
| B12 | **Payment that expires in 1 minute** | Do not pay it. Send *Get the payment* for it (change the id to `{{expiringPaymentId}}`) after ~2 minutes: `EXPIRED`, and Mailpit has **"Payment link expired"**. The expiry job asks the provider first, in case the patient paid at the last second. |

**Behind the scenes** for one payment: payment-service writes the payment **and** an event in the same
database transaction (the *outbox*); a relay publishes the event to Kafka; refund-service (to allow refunds),
transaction-service (history + ledger) and notification-service (emails, SMS, webhooks) each react to it.

**Try:** on the checkout page, click **Pay, but lose the callback**. The payment shows `PENDING` at first,
then `CAPTURED` within ~2 minutes: the recovery job asked the provider. This is how lost confirmations are
handled.

---

## C. Manual capture and cancel

The business story: some hospitals confirm the amount after the treatment, so the money is only *reserved*
when the patient pays, and *taken* when the hospital captures.

| # | Request | What happens |
|---|---|---|
| C1 | **Switch to MANUAL capture** | The configuration changes; the script waits 3 s for the change to reach payment-service through Kafka. |
| C2 | **Create a payment to capture** | `sendCheckoutLink: false`: no email this time. |
| C3 | **Patient pays** | |
| C4 | **Authorized, not captured yet** | `AUTHORIZED`: reserved, not taken. |
| C5 | **Capture the payment** | `CAPTURED`. Needs an Idempotency-Key. If the provider times out, the payment becomes `UNKNOWN` and is **checked with the provider before any retry**: never a double charge. |
| C6 | **Create a payment to cancel** | |
| C7 | **Cancel the payment** | `CANCELLED`, with a reason (e.g. the bill was corrected). |
| C8 | **Capture a cancelled payment** | `409 INVALID_STATE_TRANSITION`. The payment **state machine** only allows valid moves: `CREATED → PENDING → AUTHORIZED → CAPTURED`, plus `FAILED`, `CANCELLED`, `EXPIRED`, `UNKNOWN`. |
| C9 | **Back to AUTO capture** | Restores the setting for the next folders. |

---

## D. Refunds (refund-service)

The business story: the insurer pays part of the bill later, so the hospital gives some money back.

| # | Request | What happens |
|---|---|---|
| D1 | **Refund part of the payment** | ₹500 of the ₹2,000. `COMPLETED` with the simulated provider. If you send it immediately after B7 you may see `409 PAYMENT_NOT_YET_REFUNDABLE`: the capture event has not reached refund-service yet. Retry **with the same key**. |
| D2 | **Same refund again (replay)** | The same refund comes back: no second refund. |
| D3 | **Refund more than is left** | `422 REFUND_EXCEEDS_CAPTURED_AMOUNT` with `remainingRefundable: 1500.00`. Even many parallel refunds can never exceed the captured amount (an atomic reservation in the database). |
| D4 | **Get the refund** | Status and history. |
| D5 | **List refunds** | Filter by `paymentId` or `status`. |
| D6 | **Refund webhook endpoint** | Used by Razorpay; unsigned calls are refused. |

**Look at:** the hook receiver has `refund.completed` (the hospital is told; the patient gets no email for a
refund); dashboard → *Refunds*.

---

## E. Transactions and ledger (transaction-service)

The business story: finance needs one place with every money movement, and books that always balance.

| # | Request | What you learn |
|---|---|---|
| E1 | **Transactions of the payment** | One PAYMENT and one REFUND transaction for our payment. |
| E2 | **Transaction detail** | `timeline` (every status change in order) and `ledgerEntries` (the accounting lines). |
| E3 | **Summary report** | Captured, refunded and net amounts for the last day. |
| E4 | **Balance: what is owed to the hospital** *(OPS)* | Account `MERCHANT_PAYABLE:<hospital id>`: captured − refunded (₹1,500 after D1). |
| E5 | **Balance: provider clearing** *(OPS)* | Money the provider holds for us until settlement. Other accounts: `SETTLEMENT_BANK`, `PROVIDER_FEES`. |
| E6 | **Ledger integrity** *(OPS)* | `healthy: true`: every posting's debits equal its credits. |

**Key idea, double-entry:** every movement is booked twice, once as a debit and once as a credit, so the total
always balances. Entries are never edited or deleted; a mistake is fixed with a new reversing entry.

---

## F. Notifications (notification-service)

| # | Request | What you learn |
|---|---|---|
| F1 | **Notifications of the payment** | One row per message: `channel` WEBHOOK, EMAIL or SMS, `status` DELIVERED / PENDING / DEAD, `attempts`. |
| F2 | **Notification detail** | Every delivery attempt with the receiver's HTTP status. |
| F3 | **All notifications** *(OPS)* | Operations see every hospital's notifications; filter `status=DEAD` for the ones that gave up. |
| F4 | **Notification detail** *(OPS)* | |
| F5 | **Retry a failed notification** *(OPS)* | Re-queues a `DEAD` one; a delivered one gives `409`. |

**Try:** stop the receiver (`docker stop hook-receiver`), create and pay a payment (B1, B6), then F1:
the webhook is `PENDING` with growing `attempts` and a `nextAttemptAt` (exponential backoff). Start the
receiver again: it becomes `DELIVERED`.

**Security note:** webhooks are signed (`X-HPG-Signature`, HMAC of `timestamp.body`), never contain the
patient's email or phone, and are never sent to internal addresses (cloud metadata, private networks). This is
checked when the URL is saved and again before every delivery.

---

## G. Disputes / chargebacks

The business story: the patient tells their bank "I did not make this payment". The bank takes the money back
from the hospital (a **chargeback**) and gives the hospital a deadline to respond with evidence.

| # | Request | What happens |
|---|---|---|
| G1 | **Bank raises a chargeback** | A provider webhook (the pre-request script builds Razorpay's format and signs it). ₹1,500 is disputed and deducted. |
| G2 | **List my disputes** | `OPEN`, with `respondBy` (deadline). |
| G3 | **Get the dispute** | Amount, amount deducted, reason code. |
| G4 | **Refunds are paused** | `409 PAYMENT_DISPUTED`: a refund plus a lost chargeback would pay the patient twice. |
| G5 | **Contest with evidence** | `UNDER_REVIEW`. Needs an Idempotency-Key so the decision is never sent twice. |
| G6 | **Contest again, same key** | Replayed (`Idempotent-Replayed: true`). |
| G7 | **Accept after contesting** | `409 DISPUTE_NOT_OPEN`: statuses only move forward. (Accepting an `OPEN` dispute gives up the money.) |
| G8 | **Accept without Idempotency-Key** | `400 MISSING_IDEMPOTENCY_KEY`. |
| G9 | **Bank decides for the hospital** | Provider webhook: the dispute is won. |
| G10 | **Dispute won** | `WON`. The ledger now has a `CHARGEBACK` posting and a `CHARGEBACK_REVERSAL` posting (send E2 again to see them); refunds work again. |

**Look at:** dashboard (hospital) → *Disputes*: deadline, *Accept* and *Contest with evidence* buttons.

---

## H. Reconciliation (operations only)

The business story: every day the provider pays out and sends a **settlement report**. Operations check it
line by line against our ledger: was everything we captured paid, at the right amount, exactly once?

| # | Request | What happens |
|---|---|---|
| H1 | **Upload a settlement file** | *Body → form-data → file*: pick `samples\settlement\discrepancies.csv` if Postman cannot find it. `202`, a batch is created. The same file again → `409` (duplicate files are detected by checksum). |
| H2 | **List batches** | |
| H3 | **Get the batch** | Counts per result: `MATCHED`, `AMOUNT_MISMATCH`, `MISSING_IN_PROVIDER` (we captured, they did not pay), `MISSING_IN_INTERNAL_SYSTEM` (they paid something we do not know), `DUPLICATE`. |
| H4 | **Open differences in the batch** | The rows someone must look at. |
| H5 | **Get a record** | Both sides of one difference. |
| H6 | **Start reviewing it** | `IN_REVIEW`: tells colleagues someone is on it. |
| H7 | **Resolve it** | `RESOLVED` with a type (`PROVIDER_ERROR_RAISED`, `INTERNAL_CORRECTION_POSTED`, `TIMING_DIFFERENCE`, `ACCEPTED_AS_IS`) and a note. **Nothing is corrected automatically**: a person decides. |
| H8 | **Daily report (JSON)** | |
| H9 | **Daily report (CSV)** | Same with `Accept: text/csv`, for finance. |
| H10 | **Run against the provider's settlement API** | Fetches the report from Razorpay directly. With the simulated provider: `422`, use a file instead. |

**Look at:** dashboard (operations) → *Reconciliation*: the batch, its rows, *Review* and *Resolve*.

---

## I. Operations administration

| # | Request | What you learn |
|---|---|---|
| I1 | **List hospitals** | Filter by `status`. |
| I2 | **Get a hospital** | |
| I3 | **A hospital's configuration** | Operations can read it, but **never** the webhook secret. |
| I4 | **Suspend the hospital** | Effective within seconds, **including tokens the hospital already holds** (the gateway keeps a deny-list). |
| I5 | **Suspended hospital is blocked** | `403 MERCHANT_SUSPENDED` with the hospital's still-valid token. |
| I6 | **Re-activate the hospital** | Works again. |
| I7 | **Republish hospital snapshots** | Re-sends every hospital's settings to Kafka, e.g. after restoring a service's database. |
| I8 | **Dead letters** | Events a service could not process after retries. Normally empty. |
| I9 | **Replay a dead letter** | Sends one back after the cause is fixed; replaying twice is refused. With no dead letters this returns 400/404. |

---

## J. Security and validation (the errors are the point)

| # | Request | Expected | Why |
|---|---|---|---|
| J1 | No token | `401` | Every API needs a token |
| J2 | Forged token | `401` | Claims `OPS`, but the signature is not ours |
| J3 | Hospital calls an operations API | `403` | Role check |
| J4 | Hospital calls reconciliation | `403` | Role check |
| J5 | Another hospital's payment id | `404` | Not `403`: ids cannot even be probed |
| J6 | Payment without Idempotency-Key | `400 MISSING_IDEMPOTENCY_KEY` | |
| J7 | Invalid fields | `400 VALIDATION_FAILED` | The response lists each bad field |
| J8 | Below the hospital's minimum | `422 AMOUNT_BELOW_MINIMUM` | Business rule from A6 |
| J9 | Limit above the platform maximum | `422 LIMIT_EXCEEDS_PLATFORM_MAXIMUM` | |
| J10 | Webhook URL with credentials | `422` | Webhook URL safety |

**Read an error:** every error is a *problem details* JSON with `status`, `errorCode`, `title`, `detail` and a
`correlationId`. Search the logs for that id: `docker compose -f deploy/docker-compose.yml logs | findstr <id>`.

---

## Z. Clean-up

**Deactivate the test hospital**: final; a deactivated hospital cannot be re-activated. Skip it if you want to
keep exploring with the same hospital.

---

## Putting it together: one patient's journey

```
A1-A2  hospital registered and activated (OPS)
B1     bill created  ──► patient gets email + SMS with the link
B6     patient pays  ──► CAPTURED ──► receipt email, payment.captured webhook,
                                      ledger: hospital is owed ₹2,000
D1     ₹500 refunded ──► refund.completed webhook, hospital owed ₹1,500
G1     chargeback    ──► dispute OPEN, ₹1,500 deducted, refunds paused
G5     contested     ──► UNDER_REVIEW
G9     bank decides  ──► WON, money returned (CHARGEBACK_REVERSAL)
H1-H7  next day (OPS): settlement report reconciled, differences resolved
```

## Ideas to explore after one full pass
1. **Break idempotency on purpose:** send B1 twice quickly with the same key, then with a different amount.
2. **Use up the attempts:** decline the same payment three times (B10) and watch it become `FAILED`.
3. **Daily limit:** set `dailyLimit` to ₹3,000 (A6) and create payments until you get `422 DAILY_LIMIT_EXCEEDED`.
4. **One attempt only:** set `maxAttempts` to 1 (A6), create a payment and decline it once: `FAILED`
   straight away, and Mailpit has **"Payment not completed"**.
5. **Outage:** in a terminal, `docker compose -f deploy/docker-compose.yml pause kafka`, create and pay a payment,
   then `unpause kafka` and watch notifications and transactions catch up (folders E and F).
6. **Swagger:** every endpoint with all its fields: `http://localhost:8081/swagger-ui.html` (merchant) to
   `8086` (reconciliation).

## When something fails
- **401 everywhere:** check that the *HPG local* environment is selected and `opsClientSecret` is set (it comes
  from `deploy\env\.env`; run `import-postman.bat` again after `start-hpg.bat` created that file).
- **403 `MERCHANT_NOT_ACTIVE` on B1:** run A2 first, or wait a few seconds after it.
- **404 on a `{{...}}` id:** a previous request did not save it; send the folder from its first request.
- **Settlement upload fails:** select the CSV by hand in the request's *Body* tab.
- Anything else: note the request name, `errorCode` and `correlationId` from the Postman Console.
