# HPG Dashboard

One web app for two audiences, behind the API gateway:

| Sign in as | With | Sees |
|---|---|---|
| **Hospital** | an API key (key ID + secret) | Overview (collected / refunded / net, items needing attention), payments (create, capture, cancel, copy the patient link), refunds, transaction history with ledger entries, notification delivery, settings (webhook URL and secret, payment configuration, API keys) |
| **Operations** | the `ops-console` client (`HPG_OPS_CLIENT_SECRET`) | Overview, hospitals (activate / suspend / deactivate, configuration, amount owed), failed notifications across hospitals (re-send), ledger integrity and account balances |

## Run it

```bash
./mvnw -DskipTests package
docker compose -f deploy/docker-compose.yml --profile infra --profile core --profile refund \
  --profile transactions --profile notify --profile dashboard up -d --build
./scripts/seed-demo-data.sh      # optional: demo hospital, payments, refunds; prints sign-in details
open http://localhost:8090
```

## How it is built

- **No build step, no dependencies.** Plain HTML, CSS and JavaScript modules (`js/`), served by nginx.
  CI needs no Node toolchain and there is no third-party code in the page.
- **One origin.** nginx serves the files and forwards `/api/*` and `/oauth2/*` to the gateway
  (`GATEWAY_URL`), so the browser needs no CORS and every request still passes the gateway's JWT check
  and rate limits, then the services' own authorization and tenant isolation. The dashboard has no
  privileges of its own: it can only do what the signed-in client's token allows.
- **Sign-in** is the OAuth2 client-credentials exchange the APIs already use. The token lives in
  `sessionStorage` (cleared when the tab closes); the secret stays in memory only, to renew the
  15-minute token while the tab is open. Production would put a real user login (OIDC) in front instead
  of typing API-key secrets into a browser; that is a deliberate local-first simplification.
- **Safe rendering.** All API data is inserted as text nodes (`js/ui.js`), never as HTML; nginx sends a
  strict Content-Security-Policy (`script-src 'self'`, no inline script, no other origins).
- **State-changing actions** send a fresh `Idempotency-Key` per form, reused if the same form is
  submitted twice, so a double click never creates two payments or refunds.
- Light and dark themes follow the OS; on narrow screens the navigation becomes a top bar and tables
  become stacked cards.

| File | What |
|---|---|
| `js/api.js` | Session, token exchange, fetch wrapper (Problem Details errors) |
| `js/ui.js` | DOM helpers, formatting, tables, drawers, dialogs, toasts |
| `js/app.js` | Sign-in screen, navigation, hash router |
| `js/views/hospital.js` | Hospital pages |
| `js/views/admin.js` | Operations pages |
| `nginx/default.conf.template` | Static files, proxy to the gateway, security headers |
