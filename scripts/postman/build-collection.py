#!/usr/bin/env python3
"""Generates postman/HPG.postman_collection.json: every public API endpoint, with bodies, auth and tests.

Run after changing an endpoint:  python3 scripts/postman/build-collection.py
The collection runs top to bottom (Postman "Run collection" or newman): it registers a test hospital,
activates it, pays, refunds, raises a chargeback, reconciles, and checks the error cases on the way.
"""
import json
import pathlib
import re
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "postman" / "HPG.postman_collection.json"
NS = uuid.UUID("6f1f2c1e-9a4b-4c55-8d0e-0c1a7b3f5e21")  # stable ids so re-imports update in place


def js(text):
    return [line for line in text.strip("\n").split("\n")]


def expect(*codes):
    return f"pm.test('HTTP {' or '.join(map(str, codes))}', () => pm.expect(pm.response.code).to.be.oneOf({list(codes)}));"


def url(path, query=()):
    raw = "{{baseUrl}}" + path
    q = []
    for x in query:  # (key, value) or (key, value, disabled, description)
        k, v, off, d = (tuple(x) + (False, ""))[:4]
        q.append({"key": k, "value": v, **({"disabled": True} if off else {}), **({"description": d} if d else {})})
    enabled = [f"{x['key']}={x['value']}" for x in q if not x.get("disabled")]
    if enabled:
        raw += "?" + "&".join(enabled)
    out = {"raw": raw, "host": ["{{baseUrl}}"], "path": [p for p in path.strip("/").split("/")]}
    if q:
        out["query"] = q
    return out


def req(name, method, path, *, auth="hospital", body=None, form=None, files=None, query=(), headers=(),
        desc="", codes=(200,), tests="", pre="", idem=None):
    """auth: 'hospital' | 'ops' | None. idem: None, '{{$guid}}' or a variable reference."""
    hdrs = []
    if idem:
        hdrs.append({"key": "Idempotency-Key", "value": idem,
                     "description": "Required for money-moving calls. Same key + same body replays the first "
                                    "response (Idempotent-Replayed: true); same key + different body is 422."})
    if body is not None:
        hdrs.append({"key": "Content-Type", "value": "application/json"})
    hdrs += [{"key": k, "value": v} for k, v in headers]
    request = {"method": method, "header": hdrs, "url": url(path, query), "description": desc.strip()}
    if auth:
        request["auth"] = {"type": "bearer", "bearer": [{"key": "token", "value": "{{%sToken}}" % auth, "type": "string"}]}
    else:
        request["auth"] = {"type": "noauth"}
    if body is not None:
        raw = body if isinstance(body, str) else json.dumps(body, indent=2)
        request["body"] = {"mode": "raw", "raw": raw, "options": {"raw": {"language": "json"}}}
    elif form is not None:
        request["body"] = {"mode": "urlencoded", "urlencoded": [{"key": k, "value": v} for k, v in form]}
    elif files is not None:
        request["body"] = {"mode": "formdata", "formdata": [
            {"key": k, "type": "file", "src": v, "description": "Select a CSV, e.g. samples/settlement/discrepancies.csv"}
            for k, v in files]}
    events = []
    if pre:
        events.append({"listen": "prerequest", "script": {"type": "text/javascript", "exec": js(pre)}})
    events.append({"listen": "test", "script": {"type": "text/javascript", "exec": [expect(*codes)] + js(tests)}})
    return {"name": name, "event": events, "request": request,
            "_postman_id": str(uuid.uuid5(NS, f"{method} {path} {name}"))}


def folder(name, desc, items):
    return {"name": name, "description": desc.strip(), "item": items, "_postman_id": str(uuid.uuid5(NS, name))}


def wait(ms):
    return f"setTimeout(() => {{}}, {ms}); // give Kafka a moment to carry the event to the next service"


MONEY = lambda a: {"amount": a, "currency": "INR"}
CONFIG = {
    "enabledMethods": ["UPI", "CARD", "NETBANKING"], "captureMode": "AUTO",
    "minAmount": MONEY("10.00"), "maxAmountPerTransaction": MONEY("150000.00"), "dailyLimit": MONEY("2000000.00"),
    "paymentTtlMinutes": 30, "maxAttempts": 3, "refundWindowDays": 90,
    "webhookUrl": "{{webhookUrl}}", "notifyPatientEmail": True, "notifyPatientSms": True,
}


def config(**over):
    c = dict(CONFIG, **over)
    return json.dumps(c, indent=2)


PAYMENT_BODY = lambda ref, amount, extra="", expires=30: (
    '{\n  "merchantReference": "{{%s}}",\n  "amount": { "amount": "%s", "currency": "INR" },\n'
    '  "description": "Patient share - OPD bill",\n'
    '  "customer": { "name": "Asha Rao", "email": "{{patientEmail}}", "phone": "{{patientPhone}}" },\n'
    '  "expiresInMinutes": %d%s\n}' % (ref, amount, expires, extra))

SAVE_PAYMENT = """
const p = pm.response.json();
if (p.id) {{
  pm.collectionVariables.set('{id}', p.id);
  if (p.checkoutUrl) pm.collectionVariables.set('{token}', p.checkoutUrl.split('/checkout/')[1]);
}}
"""

DISPUTE_WEBHOOK_PRE = """
// Builds a Razorpay-format dispute event and signs it with the simulated provider's key (HMAC-SHA256).
const now = Math.floor(Date.now() / 1000);
if ('{event}' === 'payment.dispute.created') pm.collectionVariables.set('providerDisputeId', 'disp_pm_' + Date.now());
const body = JSON.stringify({{
  entity: 'event', event: '{event}', created_at: now,
  payload: {{ dispute: {{ entity: {{
    id: pm.collectionVariables.get('providerDisputeId'), entity: 'dispute',
    payment_id: pm.collectionVariables.get('providerPaymentId'),
    amount: 150000, currency: 'INR', amount_deducted: {deducted},
    reason_code: 'chargeback_unauthorised', respond_by: now + 7 * 86400,
    status: '{status}', phase: 'chargeback', created_at: now
  }} }} }}
}});
pm.variables.set('webhookBody', body);
pm.variables.set('webhookSignature', CryptoJS.HmacSHA256(body, pm.collectionVariables.get('simulatorSigningKey')).toString(CryptoJS.enc.Hex));
pm.variables.set('webhookEventId', 'evt_' + pm.variables.replaceIn('{{{{$guid}}}}'));
"""

def dispute_webhook(name, event, status, deducted, desc):
    return req(name, "POST", "/webhooks/razorpay/payments", auth=None, body="{{webhookBody}}",
               headers=[("X-Razorpay-Signature", "{{webhookSignature}}"), ("X-Razorpay-Event-Id", "{{webhookEventId}}")],
               pre=DISPUTE_WEBHOOK_PRE.format(event=event, status=status, deducted=deducted),
               tests="pm.test('applied', () => pm.expect(pm.response.json().outcome).to.be.oneOf(['APPLIED', 'NO_CHANGE']));",
               desc=desc)


COLLECTION_PRE = r"""
// Gets OAuth2 tokens automatically (client credentials) and caches them until shortly before they expire.
//   {{opsToken}}      from opsClientId / opsClientSecret (environment)
//   {{hospitalToken}} from hospitalKeyId / hospitalKeySecret (set by "Register a hospital", or put the
//                      seed script's key in the environment)
const v = k => pm.environment.get(k) || pm.collectionVariables.get(k);
let wanted = '';
try { wanted = String(pm.request.auth.parameters().get('token') || ''); } catch (e) { }
const kind = wanted.includes('opsToken') ? 'ops' : wanted.includes('hospitalToken') ? 'hospital' : null;
if (kind) {
  const id = kind === 'ops' ? v('opsClientId') : v('hospitalKeyId');
  const secret = kind === 'ops' ? v('opsClientSecret') : v('hospitalKeySecret');
  const cacheKey = id + ':' + String(secret).length;
  const fresh = pm.collectionVariables.get(kind + 'Token')
      && pm.collectionVariables.get(kind + 'TokenFor') === cacheKey
      && Date.now() < Number(pm.collectionVariables.get(kind + 'TokenExpiresAt') || 0) - 60000;
  if (!fresh && id && secret) {
    pm.sendRequest({
      url: v('baseUrl') + '/oauth2/token', method: 'POST',
      header: { 'Authorization': 'Basic ' + CryptoJS.enc.Base64.stringify(CryptoJS.enc.Utf8.parse(id + ':' + secret)) },
      body: { mode: 'urlencoded', urlencoded: [{ key: 'grant_type', value: 'client_credentials' }] }
    }, (err, res) => {
      if (err || res.code !== 200) { console.warn(kind + ' token request failed', err || res.code, res && res.text()); return; }
      const t = res.json();
      pm.collectionVariables.set(kind + 'Token', t.access_token);
      pm.collectionVariables.set(kind + 'TokenFor', cacheKey);
      pm.collectionVariables.set(kind + 'TokenExpiresAt', String(Date.now() + (t.expires_in || 900) * 1000));
    });
  } else if (!id || !secret) {
    console.warn('No credentials for the ' + kind + ' token: ' + (kind === 'ops'
      ? 'set opsClientSecret in the environment (import-postman.bat fills it from deploy/env/.env).'
      : 'run "A. Hospital onboarding > Register a hospital" first, or set hospitalKeyId / hospitalKeySecret.'));
  }
}
"""

COLLECTION_TEST = r"""
// Every error response carries a correlationId: log it so a failure can be traced in the service logs.
if (pm.response.code >= 400) {
  try { const b = pm.response.json(); console.log(pm.info.requestName, pm.response.code, b.errorCode || b.title, 'correlationId=' + b.correlationId); } catch (e) { }
}
"""

items = []

# ---------------------------------------------------------------- 0. Platform
items.append(folder("0. Platform", """
Checks that the stack answers, and the token endpoint. Tokens are fetched automatically by the collection's
pre-request script, so you never need to call /oauth2/token yourself; these requests show how it works.
""", [
    req("Get an operations token (client credentials)", "POST", "/oauth2/token", auth=None,
        form=[("grant_type", "client_credentials")],
        headers=[("Authorization", "Basic {{opsBasic}}")],
        pre="pm.variables.set('opsBasic', CryptoJS.enc.Base64.stringify(CryptoJS.enc.Utf8.parse(pm.variables.get('opsClientId') + ':' + pm.variables.get('opsClientSecret'))));",
        tests="pm.test('JWT issued, 15 minutes', () => { const t = pm.response.json(); pm.expect(t.token_type).to.eql('Bearer'); pm.expect(t.expires_in).to.be.above(0); });",
        desc="OAuth2 client-credentials grant: HTTP Basic with the client id and secret. Rate limit: 5/s per IP (burst 10)."),
    req("Public signing keys (JWKS)", "GET", "/oauth2/jwks", auth=None,
        tests="pm.test('has keys', () => pm.expect(pm.response.json().keys).to.be.an('array').that.is.not.empty);",
        desc="Public keys the gateway and services use to verify tokens. Two keys are listed during a key rotation."),
]))

# ---------------------------------------------------------------- A. Onboarding
items.append(folder("A. Hospital onboarding (merchant-service)", """
A hospital registers (public), operations activate it, then it manages its profile, configuration,
API keys and webhook secret. Registering stores the new key in hospitalKeyId / hospitalKeySecret, so every
later request runs as this test hospital.
""", [
    req("Register a hospital", "POST", "/api/v1/merchants", auth=None, codes=(201,),
        body='{\n  "legalName": "Postman Test Hospital Pvt Ltd",\n  "displayName": "Postman Test Hospital",\n'
             '  "contactEmail": "billing+{{$timestamp}}@hospital.example",\n  "contactPhone": "+919800000001"\n}',
        tests="""
const r = pm.response.json();
pm.test('pending activation, key shown once', () => { pm.expect(r.merchant.status).to.eql('PENDING_ACTIVATION'); pm.expect(r.apiKey.keySecret).to.be.a('string'); });
pm.collectionVariables.set('merchantId', r.merchant.id);
pm.collectionVariables.set('hospitalKeyId', r.apiKey.keyId);
pm.collectionVariables.set('hospitalKeySecret', r.apiKey.keySecret);
pm.collectionVariables.unset('hospitalToken');
""", desc="Public. Returns the hospital, its first API key and its webhook secret; the secrets are shown only once. "
           "Rate limit: 1/s per IP (burst 5)."),
    req("Activate the hospital (OPS)", "POST", "/api/v1/admin/merchants/{{merchantId}}/activate", auth="ops",
        body={"reason": "KYC verified"},
        tests="pm.test('ACTIVE', () => pm.expect(pm.response.json().status).to.eql('ACTIVE'));\npm.collectionVariables.unset('hospitalToken');\n" + wait(4000),
        desc="Operations only. A reason is required and audited. Payments are refused (MERCHANT_NOT_ACTIVE) until this."),
    req("My profile", "GET", "/api/v1/merchants/me",
        tests="pm.test('own hospital', () => pm.expect(pm.response.json().id).to.eql(pm.collectionVariables.get('merchantId')));",
        desc="The hospital is always taken from the token (mid claim), never from the request."),
    req("Update my profile", "PATCH", "/api/v1/merchants/me", body={"displayName": "Postman Test Hospital - East Wing", "contactPhone": "+919800000002"},
        desc="Only displayName and contactPhone can be changed by the hospital."),
    req("My configuration", "GET", "/api/v1/merchants/me/configuration"),
    req("Set my configuration (AUTO capture, webhooks, patient email + SMS)", "PUT", "/api/v1/merchants/me/configuration",
        body=config(), tests="pm.test('AUTO', () => pm.expect(pm.response.json().captureMode).to.eql('AUTO'));",
        desc="""Replaced as a whole. Changes reach payment-service through Kafka (merchant.snapshots) within seconds.
- captureMode: AUTO (capture on payment) or MANUAL (hospital captures later)
- webhookUrl: where signed hospital webhooks go. Locally HPG_ALLOW_INSECURE_WEBHOOKS=true allows http and private hosts.
- notifyPatientEmail / notifyPatientSms: receipts and checkout links to the patient."""),
    req("Create a second API key", "POST", "/api/v1/merchants/me/api-keys", codes=(201,),
        tests="pm.collectionVariables.set('secondKeyId', pm.response.json().keyId);",
        desc="For rotation: create a new key, switch the app over, revoke the old one. At most 2 active keys."),
    req("List my API keys", "GET", "/api/v1/merchants/me/api-keys",
        tests="pm.test('no secrets returned', () => pm.expect(pm.response.text()).to.not.include('keySecret'));"),
    req("Revoke the second API key", "DELETE", "/api/v1/merchants/me/api-keys/{{secondKeyId}}", codes=(204,),
        desc="Revoking the last active key is refused with 409 LAST_ACTIVE_API_KEY."),
    req("Rotate my webhook signing secret", "POST", "/api/v1/merchants/me/webhook-secret/rotate",
        tests="pm.test('new secret shown once', () => pm.expect(pm.response.json().webhookSecret).to.be.a('string'));",
        desc="Webhooks are signed with X-HPG-Signature: HMAC-SHA256 of '<timestamp>.<body>'."),
]))

# ---------------------------------------------------------------- B. Payments
items.append(folder("B. Payments and checkout (payment-service)", """
Create a payment, the patient pays on the checkout page, the hospital reads it back.
The simulated provider replaces Razorpay locally, so no real money moves.
""", [
    req("Create a payment", "POST", "/api/v1/payments", codes=(201,), idem="{{paymentKey}}",
        body=PAYMENT_BODY("merchantReference", "2000.00"),
        pre="""
pm.collectionVariables.set('paymentKey', pm.variables.replaceIn('{{$guid}}'));
pm.collectionVariables.set('merchantReference', 'BILL-' + Date.now());
""", tests=SAVE_PAYMENT.format(id="paymentId", token="checkoutToken") + """
pm.test('PENDING with a checkout link', () => { pm.expect(pm.response.json().status).to.eql('PENDING'); pm.expect(pm.response.json().checkoutUrl).to.include('/checkout/'); });
""", desc="""Needs an Idempotency-Key. merchantReference is the hospital's bill number (one live payment per bill).
sendCheckoutLink (default true) emails / texts the checkout link to the patient: see Mailpit at http://localhost:8025.
Optional fields: returnUrl, expiresInMinutes (1-1440), sendCheckoutLink."""),
    req("Same request again (idempotent replay)", "POST", "/api/v1/payments", codes=(201,), idem="{{paymentKey}}",
        body=PAYMENT_BODY("merchantReference", "2000.00"),
        tests="""
pm.test('replayed, same payment', () => { pm.expect(pm.response.headers.get('Idempotent-Replayed')).to.eql('true'); pm.expect(pm.response.json().id).to.eql(pm.collectionVariables.get('paymentId')); });
""", desc="A network retry with the same key and body returns the first response; no second payment."),
    req("Same key, different body (422)", "POST", "/api/v1/payments", codes=(422,), idem="{{paymentKey}}",
        body=PAYMENT_BODY("merchantReference", "2500.00"),
        tests="pm.test('IDEMPOTENCY_KEY_REUSED', () => pm.expect(pm.response.json().errorCode).to.eql('IDEMPOTENCY_KEY_REUSED'));"),
    req("Same bill, new key (409 duplicate)", "POST", "/api/v1/payments", codes=(409,), idem="{{$guid}}",
        body=PAYMENT_BODY("merchantReference", "2000.00"),
        tests="pm.test('DUPLICATE_PAYMENT', () => pm.expect(pm.response.json().errorCode).to.eql('DUPLICATE_PAYMENT'));"),
    req("Checkout page (what the patient sees)", "GET", "/checkout/{{checkoutToken}}", auth=None,
        desc="Open this URL in a browser: the simulated page has Pay, Decline and 'Pay without returning' buttons."),
    req("Patient pays (simulated provider)", "POST", "/checkout/{{checkoutToken}}/simulate", auth=None, codes=(200, 302, 303),
        form=[("outcome", "SUCCESS"), ("deliverCallback", "true")],
        desc="""What the buttons on the checkout page do.
- outcome=SUCCESS | FAILURE
- deliverCallback=false simulates a lost browser return: the recovery job then finds the capture within ~2 min."""),
    req("Get the payment", "GET", "/api/v1/payments/{{paymentId}}", pre=wait(2000), tests="""
const p = pm.response.json();
pm.test('CAPTURED (AUTO capture)', () => pm.expect(p.status).to.eql('CAPTURED'));
const a = (p.attempts || []).find(x => x.providerPaymentId);
if (a) pm.collectionVariables.set('providerPaymentId', a.providerPaymentId);
""", desc="Includes every attempt with the provider's payment id."),
    req("List payments", "GET", "/api/v1/payments", query=[("status", "CAPTURED"), ("size", "20"),
        ("merchantReference", "{{merchantReference}}", True, "Exact bill number"),
        ("from", "2026-01-01T00:00:00Z", True, "ISO-8601 instant"), ("to", "2030-01-01T00:00:00Z", True, ""),
        ("page", "0", True, ""), ("sort", "createdAt,desc", True, "")]),
    req("Payment declined", "POST", "/api/v1/payments", codes=(201,), idem="{{$guid}}",
        body=PAYMENT_BODY("declineReference", "750.00", ',\n  "sendCheckoutLink": false'),
        pre="pm.collectionVariables.set('declineReference', 'DECL-' + Date.now());",
        tests=SAVE_PAYMENT.format(id="declinedPaymentId", token="declineToken"),
        desc="A second payment, declined in the next request."),
    req("Patient's bank declines", "POST", "/checkout/{{declineToken}}/simulate", auth=None, codes=(200, 302, 303),
        form=[("outcome", "FAILURE"), ("deliverCallback", "true")],
        desc="The payment stays PENDING so the patient can retry, and becomes FAILED after maxAttempts (3) declines."),
    req("Checkout callback (provider redirect)", "POST", "/checkout/{{declineToken}}/callback", auth=None,
        codes=(200, 302, 303, 400, 422),
        form=[("razorpay_payment_id", "pay_test"), ("razorpay_order_id", "order_test"), ("razorpay_signature", "invalid")],
        desc="Where the provider's checkout returns the patient. A wrong signature is refused; shown for completeness."),
    req("Payment that expires in 1 minute", "POST", "/api/v1/payments", codes=(201,), idem="{{$guid}}",
        body=PAYMENT_BODY("expiryReference", "300.00", ',\n  "sendCheckoutLink": false', expires=1), pre="pm.collectionVariables.set('expiryReference', 'EXP-' + Date.now());",
        tests=SAVE_PAYMENT.format(id="expiringPaymentId", token="expiringToken"),
        desc="Do not pay it. GET it again after ~2 minutes: EXPIRED (the expiry job asks the provider first)."),
]))

# ---------------------------------------------------------------- C. Manual capture / cancel
items.append(folder("C. Manual capture and cancel", """
With captureMode MANUAL the payment stops at AUTHORIZED until the hospital captures it. Restores AUTO at the end.
""", [
    req("Switch to MANUAL capture", "PUT", "/api/v1/merchants/me/configuration", body=config(captureMode="MANUAL"),
        tests=wait(3000)),
    req("Create a payment to capture", "POST", "/api/v1/payments", codes=(201,), idem="{{$guid}}",
        body=PAYMENT_BODY("manualReference", "900.00", ',\n  "sendCheckoutLink": false'),
        pre="pm.collectionVariables.set('manualReference', 'MAN-' + Date.now());",
        tests=SAVE_PAYMENT.format(id="manualPaymentId", token="manualToken")),
    req("Patient pays", "POST", "/checkout/{{manualToken}}/simulate", auth=None, codes=(200, 302, 303),
        form=[("outcome", "SUCCESS"), ("deliverCallback", "true")]),
    req("Authorized, not captured yet", "GET", "/api/v1/payments/{{manualPaymentId}}", pre=wait(1500),
        tests="pm.test('AUTHORIZED', () => pm.expect(pm.response.json().status).to.eql('AUTHORIZED'));"),
    req("Capture the payment", "POST", "/api/v1/payments/{{manualPaymentId}}/capture", idem="{{$guid}}",
        tests="pm.test('CAPTURED', () => pm.expect(pm.response.json().status).to.eql('CAPTURED'));",
        desc="Needs an Idempotency-Key. A capture that times out at the provider is verified before any retry."),
    req("Create a payment to cancel", "POST", "/api/v1/payments", codes=(201,), idem="{{$guid}}",
        body=PAYMENT_BODY("cancelReference", "300.00", ',\n  "sendCheckoutLink": false'),
        pre="pm.collectionVariables.set('cancelReference', 'CAN-' + Date.now());",
        tests=SAVE_PAYMENT.format(id="cancelPaymentId", token="cancelToken")),
    req("Cancel the payment", "POST", "/api/v1/payments/{{cancelPaymentId}}/cancel", idem="{{$guid}}",
        body={"reason": "Bill corrected"},
        tests="pm.test('CANCELLED', () => pm.expect(pm.response.json().status).to.eql('CANCELLED'));"),
    req("Capture a cancelled payment (409)", "POST", "/api/v1/payments/{{cancelPaymentId}}/capture", idem="{{$guid}}", codes=(409,),
        tests="pm.test('INVALID_STATE_TRANSITION', () => pm.expect(pm.response.json().errorCode).to.eql('INVALID_STATE_TRANSITION'));"),
    req("Back to AUTO capture", "PUT", "/api/v1/merchants/me/configuration", body=config(), tests=wait(2000)),
]))

# ---------------------------------------------------------------- D. Refunds
items.append(folder("D. Refunds (refund-service)", """
Partial and full refunds of a captured payment. The total can never exceed the captured amount, even with
parallel requests (atomic reservation).
""", [
    req("Refund part of the payment", "POST", "/api/v1/refunds", codes=(201,), idem="{{refundKey}}",
        body='{\n  "paymentId": "{{paymentId}}",\n  "amount": { "amount": "500.00", "currency": "INR" },\n  "reason": "Insurance adjustment"\n}',
        pre="pm.collectionVariables.set('refundKey', pm.variables.replaceIn('{{$guid}}'));\n" + wait(2000),
        tests="pm.collectionVariables.set('refundId', pm.response.json().id);",
        desc="Needs an Idempotency-Key. 409 PAYMENT_NOT_YET_REFUNDABLE right after capture means the capture event has "
             "not reached refund-service yet: retry with the same key."),
    req("Same refund again (replay)", "POST", "/api/v1/refunds", codes=(201,), idem="{{refundKey}}",
        body='{\n  "paymentId": "{{paymentId}}",\n  "amount": { "amount": "500.00", "currency": "INR" },\n  "reason": "Insurance adjustment"\n}',
        tests="pm.test('same refund', () => pm.expect(pm.response.json().id).to.eql(pm.collectionVariables.get('refundId')));"),
    req("Refund more than is left (422)", "POST", "/api/v1/refunds", codes=(422,), idem="{{$guid}}",
        body='{\n  "paymentId": "{{paymentId}}",\n  "amount": { "amount": "1600.00", "currency": "INR" }\n}',
        tests="pm.test('REFUND_EXCEEDS_CAPTURED_AMOUNT', () => pm.expect(pm.response.json().errorCode).to.eql('REFUND_EXCEEDS_CAPTURED_AMOUNT'));",
        desc="The response says how much is still refundable (remainingRefundable)."),
    req("Get the refund", "GET", "/api/v1/refunds/{{refundId}}"),
    req("List refunds", "GET", "/api/v1/refunds", query=[("paymentId", "{{paymentId}}"),
        ("status", "COMPLETED", True, "PENDING, PROCESSING, COMPLETED, FAILED"), ("size", "20", True, ""), ("page", "0", True, "")]),
    req("Refund webhook endpoint (Razorpay)", "POST", "/webhooks/razorpay/refunds", auth=None, codes=(200, 400, 401, 403),
        body='{"event": "refund.processed", "payload": {}}',
        headers=[("X-Razorpay-Signature", "invalid"), ("X-Razorpay-Event-Id", "evt_{{$guid}}")],
        desc="Called by Razorpay. HMAC-signed; an unsigned call is refused. Shown for completeness."),
]))

# ---------------------------------------------------------------- E. Transactions & ledger
items.append(folder("E. Transactions and ledger (transaction-service)", """
Every payment and refund as a transaction with its timeline, the double-entry ledger, and reports.
""", [
    req("Transactions of the payment", "GET", "/api/v1/transactions", pre=wait(2000),
        query=[("paymentId", "{{paymentId}}"), ("type", "PAYMENT", True, "PAYMENT or REFUND"),
               ("status", "CAPTURED", True, ""), ("from", "2026-01-01T00:00:00Z", True, ""), ("to", "2030-01-01T00:00:00Z", True, ""),
               ("size", "20", True, ""), ("page", "0", True, "")],
        tests="""
const t = (pm.response.json().content || []).find(x => x.type === 'PAYMENT');
if (t) pm.collectionVariables.set('transactionRef', t.transactionRef);
pm.test('payment and refund recorded', () => pm.expect(pm.response.json().content.length).to.be.at.least(2));
"""),
    req("Transaction detail: timeline and ledger lines", "GET", "/api/v1/transactions/{{transactionRef}}",
        tests="pm.test('has a timeline', () => pm.expect(pm.response.json().timeline).to.be.an('array').that.is.not.empty);"),
    req("Summary report", "GET", "/api/v1/reports/summary", query=[("from", "{{reportFrom}}"), ("to", "{{reportTo}}")],
        pre="pm.variables.set('reportFrom', new Date(Date.now() - 86400000).toISOString());\npm.variables.set('reportTo', new Date(Date.now() + 3600000).toISOString());",
        desc="Captured, refunded and net amounts for the hospital in a time range."),
    req("Balance: what is owed to the hospital (OPS)", "GET", "/api/v1/admin/ledger/accounts/MERCHANT_PAYABLE:{{merchantId}}/balance", auth="ops"),
    req("Balance: provider clearing (OPS)", "GET", "/api/v1/admin/ledger/accounts/PROVIDER_CLEARING/balance", auth="ops",
        desc="Other accounts: SETTLEMENT_BANK, PROVIDER_FEES, MERCHANT_PAYABLE:<merchantId>."),
    req("Ledger integrity (OPS)", "GET", "/api/v1/admin/ledger/integrity", auth="ops",
        tests="pm.test('healthy', () => pm.expect(pm.response.json().healthy).to.be.true);",
        desc="Debits equal credits for every posting; also exported as a metric with an alert."),
]))

# ---------------------------------------------------------------- F. Notifications
items.append(folder("F. Notifications (notification-service)", """
Signed webhooks to the hospital, emails and SMS to the patient, with retries. Emails: http://localhost:8025.
""", [
    req("Notifications of the payment", "GET", "/api/v1/notifications",
        query=[("paymentId", "{{paymentId}}"), ("status", "DELIVERED", True, "PENDING, DELIVERED, DEAD"),
               ("channel", "WEBHOOK", True, "WEBHOOK, EMAIL, SMS"), ("size", "20", True, ""), ("page", "0", True, "")],
        tests="""
const n = pm.response.json().content || [];
if (n.length) pm.collectionVariables.set('notificationId', n[0].id);
pm.test('webhook, email and SMS created', () => pm.expect(n.length).to.be.at.least(1));
"""),
    req("Notification detail with every attempt", "GET", "/api/v1/notifications/{{notificationId}}"),
    req("All notifications (OPS)", "GET", "/api/v1/admin/notifications", auth="ops",
        query=[("merchantId", "{{merchantId}}"), ("status", "DEAD", True, "Failed for good"), ("channel", "WEBHOOK", True, "")]),
    req("Notification detail (OPS)", "GET", "/api/v1/admin/notifications/{{notificationId}}", auth="ops"),
    req("Retry a failed notification (OPS)", "POST", "/api/v1/admin/notifications/{{notificationId}}/retry", auth="ops",
        codes=(200, 202, 409), desc="Re-queues a DEAD notification. One that was delivered is refused (409)."),
]))

# ---------------------------------------------------------------- G. Disputes
items.append(folder("G. Disputes / chargebacks (payment-service)", """
The patient's bank raises a chargeback; refunds pause; the hospital contests; the bank decides.
The provider webhook is simulated: the pre-request script builds Razorpay's format and signs it.
""", [
    dispute_webhook("Bank raises a chargeback (provider webhook)", "payment.dispute.created", "open", 150000,
                    "Signed with X-Razorpay-Signature (HMAC-SHA256 of the raw body). ₹1,500 is deducted from the hospital."),
    req("List my disputes", "GET", "/api/v1/disputes", pre=wait(1500),
        query=[("status", "OPEN", True, "OPEN, UNDER_REVIEW, WON, LOST, ACCEPTED, CLOSED"), ("limit", "50", True, "")],
        tests="""
const d = pm.response.json().find(x => x.paymentId === pm.collectionVariables.get('paymentId'));
pm.test('dispute OPEN', () => pm.expect(d && d.status).to.eql('OPEN'));
if (d) pm.collectionVariables.set('disputeId', d.id);
"""),
    req("Get the dispute", "GET", "/api/v1/disputes/{{disputeId}}"),
    req("Refunds are paused (409)", "POST", "/api/v1/refunds", codes=(409,), idem="{{$guid}}",
        body='{\n  "paymentId": "{{paymentId}}",\n  "amount": { "amount": "100.00", "currency": "INR" }\n}',
        tests="pm.test('PAYMENT_DISPUTED', () => pm.expect(pm.response.json().errorCode).to.eql('PAYMENT_DISPUTED'));"),
    req("Contest with evidence", "POST", "/api/v1/disputes/{{disputeId}}/contest", idem="{{contestKey}}",
        body={"summary": "Consultation on file; signed consent and invoice attached"},
        pre="pm.collectionVariables.set('contestKey', pm.variables.replaceIn('{{$guid}}'));",
        tests="pm.test('UNDER_REVIEW', () => pm.expect(pm.response.json().status).to.eql('UNDER_REVIEW'));"),
    req("Contest again, same key (replay)", "POST", "/api/v1/disputes/{{disputeId}}/contest", idem="{{contestKey}}",
        body={"summary": "Consultation on file; signed consent and invoice attached"},
        tests="pm.test('replayed', () => pm.expect(pm.response.headers.get('Idempotent-Replayed')).to.eql('true'));"),
    req("Accept after contesting (409)", "POST", "/api/v1/disputes/{{disputeId}}/accept", idem="{{$guid}}", codes=(409,),
        tests="pm.test('DISPUTE_NOT_OPEN', () => pm.expect(pm.response.json().errorCode).to.eql('DISPUTE_NOT_OPEN'));",
        desc="Accepting an OPEN dispute gives up the money (status ACCEPTED)."),
    req("Accept without Idempotency-Key (400)", "POST", "/api/v1/disputes/{{disputeId}}/accept", codes=(400,),
        tests="pm.test('MISSING_IDEMPOTENCY_KEY', () => pm.expect(pm.response.json().errorCode).to.eql('MISSING_IDEMPOTENCY_KEY'));"),
    dispute_webhook("Bank decides for the hospital (provider webhook)", "payment.dispute.won", "won", 0,
                    "The deducted ₹1,500 is returned: CHARGEBACK_REVERSAL in the ledger."),
    req("Dispute won", "GET", "/api/v1/disputes/{{disputeId}}", pre=wait(1500),
        tests="pm.test('WON', () => pm.expect(pm.response.json().status).to.eql('WON'));"),
]))

# ---------------------------------------------------------------- H. Reconciliation
items.append(folder("H. Reconciliation (reconciliation-service, OPS)", """
Matches the provider's settlement report against the ledger. Upload a CSV, then review and resolve each
difference. Nothing is corrected automatically.
""", [
    req("Upload a settlement file", "POST", "/api/v1/reconciliation/settlement-files", auth="ops", codes=(202, 409),
        query=[("businessDate", "{{today}}")], files=[("file", "__REPO__/samples/settlement/discrepancies.csv")],
        pre="pm.collectionVariables.set('today', new Date().toISOString().slice(0, 10));",
        tests="if (pm.response.code === 202) pm.collectionVariables.set('batchId', pm.response.json().id);",
        desc="""multipart/form-data with a 'file' part. CSV header:
entity_id,type,order_id,amount,fee,tax,currency,settlement_id,settled_at,created_at
The same file twice is refused (409 DUPLICATE_SETTLEMENT_FILE). Samples: samples/settlement/*.csv"""),
    req("List batches", "GET", "/api/v1/reconciliation/batches", auth="ops", pre=wait(3000),
        query=[("size", "20", True, ""), ("page", "0", True, "")],
        tests="if (!pm.collectionVariables.get('batchId')) { const b = pm.response.json().content || []; if (b.length) pm.collectionVariables.set('batchId', b[0].id); }"),
    req("Get the batch", "GET", "/api/v1/reconciliation/batches/{{batchId}}", auth="ops",
        tests="pm.collectionVariables.set('reportDate', pm.response.json().businessDate);",
        desc="Counts of MATCHED, AMOUNT_MISMATCH, MISSING_IN_PROVIDER, MISSING_IN_INTERNAL_SYSTEM and DUPLICATE."),
    req("Open differences in the batch", "GET", "/api/v1/reconciliation/batches/{{batchId}}/records", auth="ops",
        query=[("resolutionStatus", "OPEN"), ("status", "AMOUNT_MISMATCH", True, "Match status")],
        tests="const r = pm.response.json().content || []; if (r.length) pm.collectionVariables.set('recordId', r[0].id);"),
    req("Get a record", "GET", "/api/v1/reconciliation/records/{{recordId}}", auth="ops"),
    req("Start reviewing it", "POST", "/api/v1/reconciliation/records/{{recordId}}/review", auth="ops", codes=(200, 409)),
    req("Resolve it", "POST", "/api/v1/reconciliation/records/{{recordId}}/resolve", auth="ops", codes=(200, 409),
        body={"resolutionType": "TIMING_DIFFERENCE", "note": "Settled in the next report", "correctionReference": None},
        desc="resolutionType: PROVIDER_ERROR_RAISED, INTERNAL_CORRECTION_POSTED, TIMING_DIFFERENCE, ACCEPTED_AS_IS."),
    req("Daily report (JSON)", "GET", "/api/v1/reconciliation/reports/daily", auth="ops", query=[("date", "{{reportDate}}")],
        desc="For the business date of the batch above. A date with no completed batch is 404 NO_COMPLETED_BATCH."),
    req("Daily report (CSV)", "GET", "/api/v1/reconciliation/reports/daily", auth="ops", query=[("date", "{{reportDate}}")],
        headers=[("Accept", "text/csv")]),
    req("Run against the provider's settlement API", "POST", "/api/v1/reconciliation/runs", auth="ops",
        codes=(201, 202, 422), body='{\n  "businessDate": "{{today}}"\n}',
        desc="Fetches the settlement report from Razorpay. With the simulated provider this is 422: upload a file instead."),
]))

# ---------------------------------------------------------------- I. Operations
items.append(folder("I. Operations administration", """
Hospital administration, suspension, and dead-letter (failed event) inspection and replay. OPS role only.
""", [
    req("List hospitals", "GET", "/api/v1/admin/merchants", auth="ops",
        query=[("status", "ACTIVE", True, "PENDING_ACTIVATION, ACTIVE, SUSPENDED, DEACTIVATED"), ("size", "20", True, ""), ("page", "0", True, "")]),
    req("Get a hospital", "GET", "/api/v1/admin/merchants/{{merchantId}}", auth="ops"),
    req("A hospital's configuration", "GET", "/api/v1/admin/merchants/{{merchantId}}/configuration", auth="ops",
        tests="pm.test('no webhook secret', () => pm.expect(pm.response.json()).to.not.have.property('webhookSecret'));"),
    req("Suspend the hospital", "POST", "/api/v1/admin/merchants/{{merchantId}}/suspend", auth="ops",
        body={"reason": "Postman test: suspension"}, tests=wait(5000),
        desc="The gateway blocks the hospital's existing tokens within seconds (Redis deny-list)."),
    req("Suspended hospital is blocked (403)", "GET", "/api/v1/payments", codes=(403,),
        tests="pm.test('MERCHANT_SUSPENDED', () => pm.expect(pm.response.json().errorCode).to.eql('MERCHANT_SUSPENDED'));"),
    req("Re-activate the hospital", "POST", "/api/v1/admin/merchants/{{merchantId}}/activate", auth="ops",
        body={"reason": "Postman test: restored"}, tests=wait(5000)),
    req("Republish hospital snapshots", "POST", "/api/v1/admin/merchants/snapshots/republish", auth="ops", codes=(200, 202),
        desc="Re-sends every hospital's settings to Kafka, e.g. after restoring a service's database."),
    req("Dead letters (failed events)", "GET", "/api/v1/admin/dead-letters", auth="ops",
        query=[("sourceTopic", "payment.events.v1", True, "Filter by original topic"), ("limit", "50", True, "")],
        tests="const d = pm.response.json(); if (d.length) { pm.collectionVariables.set('dltTopic', d[0].dltTopic); pm.collectionVariables.set('dltPartition', d[0].partition); pm.collectionVariables.set('dltOffset', d[0].offset); }",
        desc="Normally empty. Each entry names the consumer that failed and the error."),
    req("Replay a dead letter", "POST", "/api/v1/admin/dead-letters/{{dltTopic}}/{{dltPartition}}/{{dltOffset}}/replay", auth="ops",
        query=[("force", "false", True, "true replays again even if it was replayed before")], codes=(200, 202, 400, 404, 409),
        desc="Sends the event back to its original topic. A second replay is 409 DEAD_LETTER_ALREADY_REPLAYED. "
             "Without a dead letter (normal) this returns 400/404."),
]))

# ---------------------------------------------------------------- J. Security
items.append(folder("J. Security and validation (expected errors)", """
Each request should fail in a specific, safe way.
""", [
    req("No token (401)", "GET", "/api/v1/payments", auth=None, codes=(401,)),
    req("Forged token (401)", "GET", "/api/v1/payments", auth=None, codes=(401,),
        headers=[("Authorization", "Bearer {{forgedToken}}")],
        pre="""
// A well-formed JWT with a made-up signature, built here so no token-like string is committed.
const b64url = o => CryptoJS.enc.Base64.stringify(CryptoJS.enc.Utf8.parse(JSON.stringify(o))).replace(/=+$/, '').replace(/\\+/g, '-').replace(/\\//g, '_');
pm.variables.set('forgedToken', b64url({ alg: 'RS256' }) + '.' + b64url({ sub: 'x', mid: 'x', roles: ['OPS'] }) + '.c2lnbmF0dXJl');
""",
        desc="A token signed by someone else is refused, whatever it claims (here the OPS role)."),
    req("Hospital calls an operations API (403)", "GET", "/api/v1/admin/merchants", codes=(403,)),
    req("Hospital calls reconciliation (403)", "GET", "/api/v1/reconciliation/batches", codes=(403,)),
    req("Another hospital's payment id (404)", "GET", "/api/v1/payments/00000000-0000-4000-8000-000000000000", codes=(404,),
        desc="Unknown and other hospitals' ids both give 404, so ids cannot be probed."),
    req("Payment without Idempotency-Key (400)", "POST", "/api/v1/payments", codes=(400,),
        body={"merchantReference": "NO-KEY-1", "amount": MONEY("50.00")},
        tests="pm.test('MISSING_IDEMPOTENCY_KEY', () => pm.expect(pm.response.json().errorCode).to.eql('MISSING_IDEMPOTENCY_KEY'));"),
    req("Invalid fields (400)", "POST", "/api/v1/payments", codes=(400,), idem="{{$guid}}",
        body={"merchantReference": "bad ref!", "amount": {"amount": "-1", "currency": "INR"}},
        tests="pm.test('VALIDATION_FAILED', () => pm.expect(pm.response.json().errorCode).to.eql('VALIDATION_FAILED'));",
        desc="The response lists every invalid field."),
    req("Below the hospital's minimum (422)", "POST", "/api/v1/payments", codes=(422,), idem="{{$guid}}",
        body={"merchantReference": "MIN-1", "amount": MONEY("5.00")},
        tests="pm.test('AMOUNT_BELOW_MINIMUM', () => pm.expect(pm.response.json().errorCode).to.eql('AMOUNT_BELOW_MINIMUM'));"),
    req("Limit above the platform maximum (422)", "PUT", "/api/v1/merchants/me/configuration", codes=(422,),
        body=config(maxAmountPerTransaction=MONEY("99999999.00")),
        tests="pm.test('LIMIT_EXCEEDS_PLATFORM_MAXIMUM', () => pm.expect(pm.response.json().errorCode).to.eql('LIMIT_EXCEEDS_PLATFORM_MAXIMUM'));"),
    req("Webhook URL with credentials in it (422)", "PUT", "/api/v1/merchants/me/configuration", codes=(422,),
        body=config(webhookUrl="https://user:password@hooks.example.com/hpg"),
        desc="Webhook URLs must not carry credentials or fragments. With HPG_ALLOW_INSECURE_WEBHOOKS=false (production), "
             "http and private, loopback or link-local addresses such as 169.254.169.254 are refused too; locally "
             "(true) they are allowed so a container on the Docker network can receive webhooks."),
]))

# ---------------------------------------------------------------- Z. Clean-up
items.append(folder("Z. Clean-up", "Deactivates the test hospital created by this run (final; it cannot be re-activated).", [
    req("Deactivate the test hospital", "POST", "/api/v1/admin/merchants/{{merchantId}}/deactivate", auth="ops",
        body={"reason": "Postman test run finished"}),
]))

# Defaults; the "HPG local" environment overrides them (and holds opsClientSecret).
DEFAULTS = {"baseUrl": "http://localhost:8080", "opsClientId": "ops-console",
            "webhookUrl": "http://hook-receiver:8080/hpg/webhooks", "patientEmail": "asha.rao@example.com",
            "patientPhone": "+919800012345", "simulatorSigningKey": "simulated-provider-signing-key"}
variables = ["merchantId", "hospitalKeyId", "hospitalKeySecret", "secondKeyId", "paymentKey", "merchantReference",
             "paymentId", "checkoutToken", "providerPaymentId", "refundKey", "refundId", "transactionRef", "notificationId",
             "providerDisputeId", "disputeId", "contestKey", "batchId", "recordId", "today", "reportDate", "dltTopic", "dltPartition",
             "dltOffset", "hospitalToken", "opsToken"]

collection = {
    "info": {
        "_postman_id": str(uuid.uuid5(NS, "collection")),
        "name": "HPG - Healthcare Payment Gateway",
        "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
        "description": (
            "Every public endpoint of the Healthcare Payment Gateway, through the API gateway.\n\n"
            "**Run it:** select the *HPG local* environment, then *Run collection* (top to bottom). It registers a test "
            "hospital, pays, refunds, raises and wins a chargeback, reconciles, and checks the error cases. Or send "
            "requests one by one; ids are saved into collection variables as you go.\n\n"
            "**Auth:** automatic. The collection's pre-request script gets OAuth2 tokens (client credentials) for "
            "the hospital and for operations, and caches them.\n\n"
            "**Idempotency:** money-moving POSTs send an Idempotency-Key ({{$guid}} or a saved key for replays).\n\n"
            "**Errors:** RFC 7807 problem details with errorCode and correlationId (logged in the Postman console).\n\n"
            "Generated by scripts/postman/build-collection.py; see docs/testing-guide.md for the full test plan."),
    },
    "auth": {"type": "noauth"},
    "event": [
        {"listen": "prerequest", "script": {"type": "text/javascript", "exec": js(COLLECTION_PRE)}},
        {"listen": "test", "script": {"type": "text/javascript", "exec": js(COLLECTION_TEST)}},
    ],
    "variable": [{"key": k, "value": ""} for k in variables]
                + [{"key": k, "value": v} for k, v in DEFAULTS.items()],
    "item": items,
}

OUT.parent.mkdir(exist_ok=True)
OUT.write_text(json.dumps(collection, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
count = sum(len(f["item"]) for f in items)
print(f"Wrote {OUT.relative_to(ROOT)}: {len(items)} folders, {count} requests")
