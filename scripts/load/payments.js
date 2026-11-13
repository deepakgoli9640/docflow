// k6 load test: hospitals create payments and read them back through the API gateway.
//
//   k6 run scripts/load/payments.js                       # 40 iterations/s for 60 s (80 requests/s)
//   k6 run -e RATE=80 -e DURATION=120s -e MERCHANTS=10 scripts/load/payments.js
//
// Needs the stack with the SIMULATED provider (the default in deploy/env/.env) and the ops client secret:
//   OPS_SECRET=$(grep '^HPG_OPS_CLIENT_SECRET=' deploy/env/.env | cut -d= -f2-) k6 run -e OPS_SECRET=$OPS_SECRET ...
// The gateway limits each hospital to 50 requests/s, so load is spread over MERCHANTS hospitals.
// No k6 installed? scripts/load/LoadTest.java runs the same scenario with only a JDK.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import exec from 'k6/execution';
import encoding from 'k6/encoding';

const GW = __ENV.GW || 'http://localhost:8080';
const RATE = parseInt(__ENV.RATE || '40', 10);
const MERCHANTS = parseInt(__ENV.MERCHANTS || '5', 10);
const createLatency = new Trend('payment_create', true);
const getLatency = new Trend('payment_get', true);

export const options = {
  scenarios: {
    payments: {
      executor: 'constant-arrival-rate',   // open model: arrivals do not slow down when the system does
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      preAllocatedVUs: 50,
      maxVUs: 400,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    payment_create: ['p(95)<500'],
    payment_get: ['p(95)<250'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

function basic(user, pass) {
  return { Authorization: 'Basic ' + encoding.b64encode(user + ':' + pass) };
}

function body(reference) {
  return JSON.stringify({ merchantReference: reference, amount: { amount: '10.00', currency: 'INR' },
    description: 'Load test' });
}

export function setup() {
  if (!__ENV.OPS_SECRET) throw new Error('set OPS_SECRET (HPG_OPS_CLIENT_SECRET from deploy/env/.env)');
  const ops = http.post(`${GW}/oauth2/token`, { grant_type: 'client_credentials' },
    { headers: basic('ops-console', __ENV.OPS_SECRET) }).json('access_token');
  const hospitals = [];
  for (let i = 0; i < MERCHANTS; i++) {
    let reg;
    for (let attempt = 0; attempt < 20; attempt++) {   // registration is limited to 1/s per client
      reg = http.post(`${GW}/api/v1/merchants`, JSON.stringify({ legalName: `Load Hospital ${i} Pvt Ltd`,
        displayName: `Load Hospital ${i}`, contactEmail: `load-${Date.now()}-${i}@load.example` }),
      { headers: { 'Content-Type': 'application/json' } });
      if (reg.status !== 429) break;
      sleep(1.1);
    }
    if (reg.status !== 201) throw new Error(`registration failed: ${reg.status} ${reg.body}`);
    const r = reg.json();
    http.post(`${GW}/api/v1/admin/merchants/${r.merchant.id}/activate`, JSON.stringify({ reason: 'load test' }),
      { headers: { Authorization: `Bearer ${ops}`, 'Content-Type': 'application/json' } });
    const token = http.post(`${GW}/oauth2/token`, { grant_type: 'client_credentials' },
      { headers: basic(r.apiKey.keyId, r.apiKey.keySecret) }).json('access_token');
    // Activation reaches payment-service asynchronously (merchant.snapshots.v1): wait until it accepts payments.
    let ok = false;
    for (let attempt = 0; attempt < 60 && !ok; attempt++) {
      const p = http.post(`${GW}/api/v1/payments`, body(`WARMUP-${uuid()}`), { headers: {
        Authorization: `Bearer ${token}`, 'Idempotency-Key': uuid(), 'Content-Type': 'application/json' } });
      ok = p.status === 201;
      if (!ok) sleep(0.5);
    }
    if (!ok) throw new Error(`hospital ${i} never became payable`);
    hospitals.push(token);
  }
  return { hospitals };
}

export default function (data) {
  const token = data.hospitals[exec.scenario.iterationInTest % data.hospitals.length];
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  const created = http.post(`${GW}/api/v1/payments`, body(`LOAD-${uuid()}`),
    { headers: Object.assign({ 'Idempotency-Key': uuid() }, headers), tags: { name: 'POST /payments' } });
  createLatency.add(created.timings.duration);
  if (!check(created, { 'created 201': (r) => r.status === 201 })) return;
  const got = http.get(`${GW}/api/v1/payments/${created.json('id')}`, { headers, tags: { name: 'GET /payments/{id}' } });
  getLatency.add(got.timings.duration);
  check(got, { 'read 200': (r) => r.status === 200 });
}
