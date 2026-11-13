// Hospital (MERCHANT role) views. Everything is scoped to the signed-in hospital by the services
// themselves: the merchant id comes from the token, never from the browser.
import { api, get, newIdempotencyKey } from '../api.js';
import {
  h, clear, money, dateTime, relative, shortId, humanize, badge, button, spinner, errorBox, table, pager, select,
  fields, card, stat, timeline, copyButton, toast, drawer, formDialog, confirmDialog, toAmount, emptyState,
} from '../ui.js';
import { navigate, pageHeader } from '../router.js';

const PAYMENT_STATUSES = ['CREATED', 'PENDING', 'PROCESSING', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'CANCELLED', 'EXPIRED', 'UNKNOWN'];
const REFUND_STATUSES = ['CREATED', 'PROCESSING', 'UNKNOWN', 'COMPLETED', 'FAILED'];
const TXN_STATUSES = ['INITIATED', 'AUTHORIZED', 'CAPTURED', 'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED'];
const all = (label) => [['', label]];
const opts = (list) => list.map((s) => [s, humanize(s)]);

/** Renders a filterable, paged list into {@code main}. */
async function listPage(main, { header, filters, load, render }) {
  const state = { page: 0, ...filters.initial };
  const toolbar = h('div', { class: 'toolbar' });
  const results = h('div');
  const reload = async () => {
    clear(results, spinner());
    try {
      const data = await load(state);
      clear(results, render(data.content), pager(data.page, (p) => { state.page = p; reload(); }));
    } catch (e) {
      clear(results, errorBox(e));
    }
  };
  const set = (k) => (v) => { state[k] = v; state.page = 0; reload(); };
  clear(toolbar, filters.controls(state, set));
  clear(main, header, toolbar, results);
  await reload();
  return reload;
}

// ============================================================ Overview
export async function overview(main) {
  let days = 30;
  const body = h('div');
  const periodSelect = select('period', [['7', 'Last 7 days'], ['30', 'Last 30 days'], ['90', 'Last 90 days']], '30',
    (v) => { days = Number(v); load(); }, 'Period');
  clear(main, pageHeader('Overview', 'Money collected and refunded, and what needs attention.', periodSelect,
    button('New payment', () => newPaymentDialog(() => load()), { variant: 'primary' })), body);

  async function load() {
    clear(body, spinner());
    const to = new Date();
    const from = new Date(to.getTime() - days * 86400_000);
    const [summary, authorized, dead, recent] = await Promise.all([
      get('/api/v1/reports/summary', { from: from.toISOString(), to: to.toISOString() }).catch((e) => e),
      get('/api/v1/payments', { status: 'AUTHORIZED', size: 1 }).catch(() => null),
      get('/api/v1/notifications', { status: 'DEAD', size: 1 }).catch(() => null),
      get('/api/v1/payments', { size: 8 }),
    ]);
    const totals = Array.isArray(summary) ? summary : [];
    const stats = totals.length
      ? totals.map((t) => h('div', { class: 'stats' },
        stat('Collected', money(t.capturedAmount), `${t.capturedCount} payment${t.capturedCount === 1 ? '' : 's'}`),
        stat('Refunded', money(t.refundedAmount), `${t.refundedCount} refund${t.refundedCount === 1 ? '' : 's'}`),
        stat('Net', money(t.netAmount), t.currency)))
      : [h('div', { class: 'stats' }, stat('Collected', '—', 'No captured payments in this period'),
        stat('Refunded', '—'), stat('Net', '—'))];
    const attention = [];
    if (authorized?.page.totalElements) {
      attention.push(h('li', {}, h('a', { href: '#/payments?status=AUTHORIZED' },
        `${authorized.page.totalElements} payment${authorized.page.totalElements === 1 ? ' is' : 's are'} authorized and waiting to be captured`)));
    }
    if (dead?.page.totalElements) {
      attention.push(h('li', {}, h('a', { href: '#/notifications?status=DEAD' },
        `${dead.page.totalElements} notification${dead.page.totalElements === 1 ? '' : 's'} could not be delivered`)));
    }
    clear(body,
      summary instanceof Error ? errorBox(summary) : stats,
      attention.length ? h('div', { class: 'alert alert-warn' }, h('strong', {}, 'Needs attention'), h('ul', {}, attention)) : null,
      card('Recent payments', paymentsTable(recent.content),
        h('p', {}, h('a', { href: '#/payments' }, 'All payments →'))));
  }
  await load();
}

// ============================================================ Payments
function paymentsTable(rows) {
  return table([
    { label: 'Bill reference', value: (p) => h('span', { class: 'strong' }, p.merchantReference) },
    { label: 'Amount', value: (p) => money(p.amount), class: 'num' },
    { label: 'Status', value: (p) => [badge(p.status), p.lateCapture ? h('span', { class: 'badge badge-warn' }, 'Late capture') : null] },
    { label: 'Created', value: (p) => h('span', { title: dateTime(p.createdAt) }, relative(p.createdAt)) },
    { label: 'Payment ID', value: (p) => h('code', {}, shortId(p.id)) },
  ], rows, { onRowClick: (p) => paymentDrawer(p.id), empty: 'No payments match.' });
}

export async function payments(main, params) {
  if (params.get('id')) paymentDrawer(params.get('id'));
  let refInput;
  const reload = await listPage(main, {
    header: pageHeader('Payments', 'Every payment request sent to a patient.',
      button('New payment', () => newPaymentDialog(() => reload()), { variant: 'primary' })),
    filters: {
      initial: { status: params.get('status') || '', merchantReference: '' },
      controls: (state, set) => {
        refInput = h('input', { type: 'search', placeholder: 'Bill reference', 'aria-label': 'Bill reference',
          value: state.merchantReference });
        refInput.addEventListener('change', () => set('merchantReference')(refInput.value.trim()));
        return [select('status', [...all('All statuses'), ...opts(PAYMENT_STATUSES)], state.status, set('status'), 'Status'), refInput];
      },
    },
    load: (s) => get('/api/v1/payments', { status: s.status, merchantReference: s.merchantReference, page: s.page, size: 20 }),
    render: paymentsTable,
  });
}

function paymentDrawer(id) {
  drawer('Payment', async (ctx) => {
    const [p, refundsPage] = await Promise.all([
      get(`/api/v1/payments/${id}`),
      get('/api/v1/refunds', { paymentId: id, size: 50 }).catch(() => ({ content: [] })),
    ]);
    const refunds = refundsPage.content;
    const refunded = refunds.filter((r) => r.status === 'COMPLETED').reduce((sum, r) => sum + Number(r.amount.amount), 0);
    const inFlight = refunds.filter((r) => ['CREATED', 'PROCESSING', 'UNKNOWN'].includes(r.status))
      .reduce((sum, r) => sum + Number(r.amount.amount), 0);
    const remaining = Math.max(0, Number(p.amount.amount) - refunded - inFlight).toFixed(2);
    const after = () => { ctx.refresh(); };

    const actions = [];
    if (p.checkoutUrl && ['CREATED', 'PENDING'].includes(p.status)) {
      actions.push(h('a', { class: 'btn btn-secondary', href: p.checkoutUrl, target: '_blank', rel: 'noopener' }, 'Open checkout page'),
        copyButton(p.checkoutUrl, 'Copy payment link'));
    }
    if (p.status === 'AUTHORIZED') {
      actions.push(button('Capture', () => confirmDialog('Capture payment', `Capture ${money(p.amount)} from the patient's authorization?`,
        async () => { await api(`/api/v1/payments/${id}/capture`, { method: 'POST', idempotencyKey: newIdempotencyKey() }); toast('Payment captured'); after(); },
        { confirmLabel: 'Capture' }), { variant: 'primary' }));
    }
    if (['CREATED', 'PENDING', 'AUTHORIZED'].includes(p.status)) {
      actions.push(button('Cancel payment', () => confirmDialog('Cancel payment',
        'The patient will no longer be able to pay this request.',
        async (v) => { await api(`/api/v1/payments/${id}/cancel`, { method: 'POST', idempotencyKey: newIdempotencyKey(), body: { reason: v.reason } }); toast('Payment cancelled'); after(); },
        { confirmLabel: 'Cancel payment', danger: true, reason: 'Reason' }), { variant: 'danger' }));
    }
    if (p.status === 'CAPTURED' && Number(remaining) > 0) {
      actions.push(button('Refund', () => refundDialog({ paymentId: id, amount: remaining, max: remaining, currency: p.amount.currency }, after),
        { variant: 'secondary' }));
    }

    return h('div', { class: 'stack' },
      h('div', { class: 'detail-hero' }, h('p', { class: 'hero-amount' }, money(p.amount)), badge(p.status),
        p.lateCapture && h('span', { class: 'badge badge-warn' }, 'Late capture')),
      actions.length && h('div', { class: 'actions' }, actions),
      p.lateCapture && h('div', { class: 'alert alert-warn' }, 'The provider captured this payment after it had expired, '
        + 'been cancelled or failed. The money was received; check whether the bill was settled another way.'),
      fields([
        ['Bill reference', p.merchantReference],
        ['Payment ID', h('code', {}, p.id)],
        ['Description', p.description],
        ['Capture mode', humanize(p.captureMode)],
        ['Patient', p.customer ? [p.customer.name, p.customer.email, p.customer.phone].filter(Boolean).join(' · ') : null],
        ['Created', dateTime(p.createdAt)],
        ['Expires', ['CREATED', 'PENDING'].includes(p.status) ? dateTime(p.expiresAt) : null],
        ['Captured', p.capturedAt ? dateTime(p.capturedAt) : null],
        p.failureReason && ['Failure', `${p.failureReason}${p.failureCode ? ` (${p.failureCode})` : ''}`],
        refunds.length && ['Refunded', `${money({ amount: refunded.toFixed(2), currency: p.amount.currency })}`
          + (inFlight ? ` (+ ${money({ amount: inFlight.toFixed(2), currency: p.amount.currency })} in progress)` : '')],
      ]),
      h('h3', {}, 'Status history'),
      timeline((p.statusHistory || []).map((c) => ({ status: c.to, at: c.at,
        text: [humanize(c.source), c.reason].filter(Boolean).join(' — ') }))),
      p.attempts?.length ? [h('h3', {}, 'Payment attempts'), table([
        { label: 'Provider ID', value: (a) => h('code', {}, a.providerPaymentId) },
        { label: 'Method', value: (a) => humanize(a.method) },
        { label: 'Status', value: (a) => badge(a.status) },
        { label: 'Error', value: (a) => a.errorDescription || '—' },
      ], p.attempts)] : null,
      refunds.length ? [h('h3', {}, 'Refunds'), refundsTable(refunds)] : null,
      h('p', {}, h('a', { href: `#/transactions?paymentId=${id}`, onClick: () => ctx.close() }, 'View in transaction history →')));
  });
}

function newPaymentDialog(onDone) {
  const key = newIdempotencyKey();
  formDialog('New payment', [
    { name: 'merchantReference', label: 'Bill reference', required: true, maxlength: 64, pattern: '[A-Za-z0-9._\\/\\-]+',
      hint: 'Your bill number. Letters, digits and . _ / - only. One open payment per bill.' },
    { name: 'amount', label: 'Amount (INR)', required: true, inputmode: 'decimal', placeholder: '1500.00' },
    { name: 'description', label: 'Description', maxlength: 255, placeholder: 'Patient share after insurance' },
    { name: 'name', label: 'Patient name', maxlength: 100 },
    { name: 'email', label: 'Patient email', type: 'email', hint: 'Receives the receipt.' },
    { name: 'phone', label: 'Patient phone', type: 'tel', placeholder: '+919800001234' },
    { name: 'expiresInMinutes', label: 'Link valid for (minutes)', type: 'number', min: 1, max: 1440 },
  ], async (v) => {
    const customer = v.name || v.email || v.phone ? { name: v.name, email: v.email, phone: v.phone } : undefined;
    const { data } = await api('/api/v1/payments', {
      method: 'POST', idempotencyKey: key,
      body: { merchantReference: v.merchantReference, amount: { amount: toAmount(v.amount), currency: 'INR' },
        description: v.description, customer, expiresInMinutes: v.expiresInMinutes ? Number(v.expiresInMinutes) : undefined },
    });
    onDone?.();
    return h('div', { class: 'stack' },
      h('div', { class: 'alert alert-good' }, `Payment of ${money(data.amount)} created for ${data.merchantReference}.`),
      data.checkoutUrl
        ? [h('p', {}, 'Send this link to the patient:'), h('div', { class: 'link-box' }, h('code', {}, data.checkoutUrl), copyButton(data.checkoutUrl)),
          h('a', { class: 'btn btn-secondary', href: data.checkoutUrl, target: '_blank', rel: 'noopener' }, 'Open checkout page')]
        : h('p', { class: 'muted' }, `Status: ${humanize(data.status)}. The payment link will appear on the payment once the provider order exists.`));
  }, { submitLabel: 'Create payment' });
}

// ============================================================ Refunds
function refundsTable(rows) {
  return table([
    { label: 'Created', value: (r) => h('span', { title: dateTime(r.createdAt) }, relative(r.createdAt)) },
    { label: 'Amount', value: (r) => money(r.amount), class: 'num' },
    { label: 'Status', value: (r) => badge(r.status) },
    { label: 'Reason', value: (r) => r.reason || '—' },
    { label: 'Payment', value: (r) => h('code', {}, shortId(r.paymentId)) },
  ], rows, { onRowClick: (r) => refundDrawer(r.id), empty: 'No refunds yet.' });
}

export async function refunds(main, params) {
  const reload = await listPage(main, {
    header: pageHeader('Refunds', 'Full and partial refunds of captured payments.',
      button('New refund', () => refundDialog({ paymentId: params.get('paymentId') || '' }, () => reload()), { variant: 'primary' })),
    filters: {
      initial: { status: params.get('status') || '', paymentId: params.get('paymentId') || '' },
      controls: (state, set) => [
        select('status', [...all('All statuses'), ...opts(REFUND_STATUSES)], state.status, set('status'), 'Status'),
        state.paymentId && h('span', { class: 'chip' }, `Payment ${shortId(state.paymentId)}`,
          button('×', () => navigate('refunds'), { variant: 'ghost', title: 'Clear filter' })),
      ],
    },
    load: (s) => get('/api/v1/refunds', { status: s.status, paymentId: s.paymentId, page: s.page, size: 20 }),
    render: refundsTable,
  });
}

function refundDrawer(id) {
  drawer('Refund', async (ctx) => {
    const r = await get(`/api/v1/refunds/${id}`);
    return h('div', { class: 'stack' },
      h('div', { class: 'detail-hero' }, h('p', { class: 'hero-amount' }, money(r.amount)), badge(r.status)),
      r.status === 'UNKNOWN' && h('div', { class: 'alert alert-warn' },
        'The provider did not answer in time. HPG checks with the provider before retrying, so the patient is never refunded twice.'),
      fields([
        ['Refund ID', h('code', {}, r.id)],
        ['Payment', h('a', { href: `#/payments?id=${r.paymentId}`, onClick: () => ctx.close() }, r.paymentId)],
        ['Reason', r.reason],
        ['Provider reference', r.providerRefundId],
        r.failureReason && ['Failure', `${r.failureReason}${r.failureCode ? ` (${r.failureCode})` : ''}`],
        ['Created', dateTime(r.createdAt)],
        ['Completed', r.completedAt ? dateTime(r.completedAt) : null],
      ]),
      h('h3', {}, 'Status history'),
      timeline((r.statusHistory || []).map((c) => ({ status: c.to, at: c.at, text: c.reason }))));
  });
}

function refundDialog({ paymentId, amount, max, currency = 'INR' }, onDone) {
  const key = newIdempotencyKey();
  formDialog('Refund', [
    { name: 'paymentId', label: 'Payment ID', required: true, value: paymentId, hint: 'The full ID of a captured payment.' },
    { name: 'amount', label: `Amount (${currency})`, required: true, value: amount, inputmode: 'decimal',
      hint: max ? `Up to ${money({ amount: max, currency })} can still be refunded.` : 'Partial refunds are allowed.' },
    { name: 'reason', label: 'Reason', type: 'textarea', maxlength: 255 },
  ], async (v) => {
    const { data } = await api('/api/v1/refunds', {
      method: 'POST', idempotencyKey: key,
      body: { paymentId: v.paymentId, amount: { amount: toAmount(v.amount), currency }, reason: v.reason },
    });
    toast(`Refund of ${money(data.amount)}: ${humanize(data.status)}`, data.status === 'FAILED' ? 'bad' : 'good');
    onDone?.();
  }, { submitLabel: 'Refund', intro: 'The refund goes back to the patient\'s original payment method.' });
}

// ============================================================ Transactions
export async function transactions(main, params) {
  const toDateInput = (d) => d.toISOString().slice(0, 10);
  await listPage(main, {
    header: pageHeader('Transactions', 'History and status of every payment and refund, with its ledger entries.'),
    filters: {
      initial: { type: params.get('type') || '', status: '', from: '', to: '', paymentId: params.get('paymentId') || '' },
      controls: (state, set) => {
        const date = (k, label) => {
          const el = h('input', { type: 'date', 'aria-label': label, value: state[k], max: toDateInput(new Date()) });
          el.addEventListener('change', () => set(k)(el.value));
          return h('label', { class: 'inline' }, h('span', { class: 'muted small' }, label), el);
        };
        return [
          select('type', [...all('Payments and refunds'), ['PAYMENT', 'Payments'], ['REFUND', 'Refunds']], state.type, set('type'), 'Type'),
          select('status', [...all('All statuses'), ...opts(TXN_STATUSES)], state.status, set('status'), 'Status'),
          date('from', 'From'), date('to', 'To'),
          state.paymentId && h('span', { class: 'chip' }, `Payment ${shortId(state.paymentId)}`,
            button('×', () => navigate('transactions'), { variant: 'ghost', title: 'Clear filter' })),
        ];
      },
    },
    load: (s) => get('/api/v1/transactions', {
      type: s.type, status: s.status, paymentId: s.paymentId, page: s.page, size: 20,
      from: s.from ? new Date(s.from + 'T00:00:00').toISOString() : undefined,
      to: s.to ? new Date(new Date(s.to + 'T00:00:00').getTime() + 86400_000).toISOString() : undefined,
    }),
    render: (rows) => table([
      { label: 'Transaction', value: (t) => h('code', {}, t.transactionRef) },
      { label: 'Type', value: (t) => humanize(t.type) },
      { label: 'Bill reference', value: (t) => t.merchantReference || '—' },
      { label: 'Amount', value: (t) => (t.type === 'REFUND' ? '− ' : '') + money(t.amount), class: 'num' },
      { label: 'Status', value: (t) => badge(t.status) },
      { label: 'Date', value: (t) => dateTime(t.completedAt || t.initiatedAt) },
    ], rows, { onRowClick: (t) => transactionDrawer(t.transactionRef), empty: 'No transactions match.' }),
  });
}

function transactionDrawer(ref) {
  drawer(`Transaction ${ref}`, async (ctx) => {
    const t = await get(`/api/v1/transactions/${encodeURIComponent(ref)}`);
    return h('div', { class: 'stack' },
      h('div', { class: 'detail-hero' }, h('p', { class: 'hero-amount' }, money(t.amount)), badge(t.status),
        h('span', { class: 'muted' }, humanize(t.type))),
      fields([
        ['Reference', h('code', {}, t.transactionRef)],
        ['Bill reference', t.merchantReference],
        ['Payment', h('a', { href: `#/payments?id=${t.paymentId}`, onClick: () => ctx.close() }, t.paymentId)],
        t.refundId && ['Refund ID', h('code', {}, t.refundId)],
        ['Provider reference', t.providerReference],
        t.failureReason && ['Failure', t.failureReason],
        ['Started', dateTime(t.initiatedAt)],
        ['Completed', t.completedAt ? dateTime(t.completedAt) : null],
      ]),
      h('h3', {}, 'Timeline'),
      timeline((t.timeline || []).map((e) => ({ status: e.status, at: e.occurredAt,
        text: humanize(e.eventType.replace(/([a-z])([A-Z])/g, '$1_$2')) }))),
      h('h3', {}, 'Ledger entries'),
      t.ledgerEntries?.length ? table([
        { label: 'Account', value: (e) => h('code', {}, e.accountCode.startsWith('MERCHANT_PAYABLE:') ? 'MERCHANT_PAYABLE' : e.accountCode) },
        { label: 'Debit', value: (e) => (e.direction === 'DEBIT' ? money(e.amount) : ''), class: 'num' },
        { label: 'Credit', value: (e) => (e.direction === 'CREDIT' ? money(e.amount) : ''), class: 'num' },
        { label: 'Posted', value: (e) => dateTime(e.postedAt) },
      ], t.ledgerEntries) : emptyState('No ledger entries', 'Entries are posted when money moves: a capture or a completed refund.'));
  });
}

// ============================================================ Notifications
function notificationsTable(rows, onClick) {
  return table([
    { label: 'Event', value: (n) => n.eventType },
    { label: 'To', value: (n) => (n.channel === 'WEBHOOK' ? 'Your webhook' : `Patient · ${n.recipient || ''}`) },
    { label: 'Status', value: (n) => badge(n.status) },
    { label: 'Attempts', value: (n) => n.attempts, class: 'num' },
    { label: 'Last error', value: (n) => h('span', { class: 'clip', title: n.lastError || '' }, n.lastError || '—') },
    { label: 'Created', value: (n) => h('span', { title: dateTime(n.createdAt) }, relative(n.createdAt)) },
  ], rows, { onRowClick: onClick, empty: 'No notifications match.' });
}

export async function notifications(main, params) {
  await listPage(main, {
    header: pageHeader('Notifications', 'Webhooks sent to your systems and emails sent to patients.'),
    filters: {
      initial: { status: params.get('status') || '', channel: '' },
      controls: (state, set) => [
        select('status', [...all('All statuses'), ...opts(['PENDING', 'DELIVERED', 'SKIPPED', 'DEAD'])], state.status, set('status'), 'Status'),
        select('channel', [...all('All channels'), ['WEBHOOK', 'Webhook'], ['EMAIL', 'Email']], state.channel, set('channel'), 'Channel'),
      ],
    },
    load: (s) => get('/api/v1/notifications', { status: s.status, channel: s.channel, page: s.page, size: 20 }),
    render: (rows) => notificationsTable(rows, (n) => notificationDrawer(`/api/v1/notifications/${n.id}`)),
  });
}

export function notificationDrawer(path, extra) {
  drawer('Notification', async (ctx) => {
    const n = await get(path);
    return h('div', { class: 'stack' },
      h('div', { class: 'detail-hero' }, h('p', { class: 'hero-amount small-hero' }, n.eventType), badge(n.status)),
      n.status === 'SKIPPED' && n.channel === 'WEBHOOK' && h('div', { class: 'alert' },
        'Skipped because no webhook URL is configured. Add one under Settings to receive events.'),
      n.status === 'DEAD' && h('div', { class: 'alert alert-error' },
        'Delivery gave up after repeated failures. Operations can re-send it once your endpoint is fixed.'),
      fields([
        ['Channel', n.channel === 'WEBHOOK' ? 'Webhook to your systems' : 'Email to patient'],
        n.recipient && ['Recipient', n.recipient],
        ['Event ID', h('code', {}, n.eventId)],
        ['Payment', h('a', { href: `#/payments?id=${n.paymentId}`, onClick: () => ctx.close() }, n.paymentId)],
        ['Attempts', n.attempts],
        ['Last response', n.lastResponseStatus ? `HTTP ${n.lastResponseStatus}` : null],
        ['Next attempt', n.nextAttemptAt ? dateTime(n.nextAttemptAt) : null],
        ['Delivered', n.deliveredAt ? dateTime(n.deliveredAt) : null],
      ]),
      extra?.(n, ctx),
      h('h3', {}, 'Delivery attempts'),
      n.attemptLog?.length ? table([
        { label: '#', value: (a) => a.attemptNo, class: 'num' },
        { label: 'Outcome', value: (a) => badge(a.outcome) },
        { label: 'HTTP', value: (a) => a.httpStatus ?? '—' },
        { label: 'Error', value: (a) => a.error || '—' },
        { label: 'Took', value: (a) => `${a.durationMs} ms`, class: 'num' },
        { label: 'At', value: (a) => dateTime(a.attemptedAt) },
      ], n.attemptLog) : emptyState('Not attempted yet.'));
  });
}

// ============================================================ Settings
export async function settings(main) {
  clear(main, pageHeader('Settings', 'Your hospital profile, payment configuration, webhook and API keys.'), spinner());
  const [me, config, keys] = await Promise.all([
    get('/api/v1/merchants/me'), get('/api/v1/merchants/me/configuration'), get('/api/v1/merchants/me/api-keys'),
  ]);
  const reload = () => settings(main);
  clear(main,
    pageHeader('Settings', 'Your hospital profile, payment configuration, webhook and API keys.'),
    h('div', { class: 'grid-2' },
      card('Hospital', fields([
        ['Name', me.displayName], ['Legal name', me.legalName], ['Status', badge(me.status)],
        me.statusReason && ['Status reason', me.statusReason],
        ['Contact', [me.contactEmail, me.contactPhone].filter(Boolean).join(' · ')],
        ['Merchant ID', h('code', {}, me.id)],
      ])),
      card('Webhook',
        fields([
          ['URL', config.webhookUrl ? h('code', {}, config.webhookUrl) : h('span', { class: 'muted' }, 'Not configured')],
          ['Signing secret', config.webhookSecretConfigured ? 'Configured' : 'Not set'],
        ]),
        h('p', { class: 'muted small' }, 'Each webhook carries X-HPG-Signature: v1=HMAC-SHA256(secret, "<X-HPG-Timestamp>.<body>"). '
          + 'Verify it and reject timestamps older than 5 minutes.'),
        h('div', { class: 'actions' },
          button('Edit webhook URL', () => configDialog(config, reload, true)),
          button('Rotate secret', () => confirmDialog('Rotate webhook secret',
            'A new secret replaces the current one immediately. Update your systems right away.', async () => {
              const { data } = await api('/api/v1/merchants/me/webhook-secret/rotate', { method: 'POST' });
              return secretShown('New webhook signing secret', data.webhookSecret);
            }, { confirmLabel: 'Rotate', danger: true }))))),
    card('Payment configuration',
      fields([
        ['Payment methods', (config.enabledMethods || []).map(humanize).join(', ')],
        ['Capture', config.captureMode === 'AUTO' ? 'Automatic' : 'Manual (you capture authorized payments)'],
        ['Per payment', `${money(config.minAmount)} – ${money(config.maxAmountPerTransaction)}`],
        ['Daily limit', money(config.dailyLimit)],
        ['Payment link valid for', `${config.paymentTtlMinutes} minutes`],
        ['Attempts per payment', config.maxAttempts],
        ['Refund window', `${config.refundWindowDays} days`],
        ['Patient emails', config.notifyPatientEmail ? 'On' : 'Off'],
        ['Last changed', dateTime(config.updatedAt)],
      ]),
      h('p', { class: 'muted small' }, 'Payment processing picks up changes when operations syncs your configuration.'),
      h('div', { class: 'actions' }, button('Edit configuration', () => configDialog(config, reload, false)))),
    card('API keys',
      h('p', { class: 'muted small' }, 'Up to two active keys, so you can rotate without downtime. Secrets are shown once.'),
      table([
        { label: 'Key ID', value: (k) => h('code', {}, k.keyId) },
        { label: 'Status', value: (k) => badge(k.status) },
        { label: 'Created', value: (k) => dateTime(k.createdAt) },
        { label: 'Last used', value: (k) => (k.lastUsedAt ? relative(k.lastUsedAt) : 'Never') },
        { label: '', value: (k) => (k.status === 'ACTIVE' ? button('Revoke', () => confirmDialog('Revoke API key',
          `Systems using ${k.keyId} will stop working when their current token expires.`, async () => {
            await api(`/api/v1/merchants/me/api-keys/${encodeURIComponent(k.keyId)}`, { method: 'DELETE' });
            toast('API key revoked'); reload();
          }, { confirmLabel: 'Revoke', danger: true }), { variant: 'ghost' }) : '') },
      ], keys),
      h('div', { class: 'actions' }, button('Create API key', () => confirmDialog('Create API key',
        'A new key and secret will be created.', async () => {
          const { data } = await api('/api/v1/merchants/me/api-keys', { method: 'POST' });
          setTimeout(reload, 0);
          return secretShown(`API key ${data.keyId}`, data.keySecret, data.keyId);
        }, { confirmLabel: 'Create' })))));
}

function secretShown(title, secret, keyId) {
  return h('div', { class: 'stack' },
    h('div', { class: 'alert alert-warn' }, h('strong', {}, title), h('p', {}, 'Copy it now. It cannot be shown again.')),
    keyId && h('div', { class: 'link-box' }, h('span', { class: 'muted small' }, 'Key ID'), h('code', {}, keyId), copyButton(keyId)),
    h('div', { class: 'link-box' }, h('span', { class: 'muted small' }, 'Secret'), h('code', {}, secret), copyButton(secret)));
}

function configDialog(c, onDone, webhookOnly) {
  const spec = webhookOnly
    ? [{ name: 'webhookUrl', label: 'Webhook URL', type: 'url', value: c.webhookUrl, placeholder: 'https://his.hospital.example/hpg/events',
      hint: 'HTTPS only. Leave empty to stop webhooks.' }]
    : [
      { name: 'enabledMethods', label: 'Payment methods', type: 'checkboxes', value: c.enabledMethods,
        options: [['CARD', 'Card'], ['UPI', 'UPI'], ['NETBANKING', 'Net banking'], ['WALLET', 'Wallet']] },
      { name: 'captureMode', label: 'Capture', type: 'select', value: c.captureMode,
        options: [['AUTO', 'Automatic'], ['MANUAL', 'Manual']] },
      { name: 'minAmount', label: 'Minimum per payment (INR)', required: true, value: c.minAmount.amount, inputmode: 'decimal' },
      { name: 'maxAmountPerTransaction', label: 'Maximum per payment (INR)', required: true, value: c.maxAmountPerTransaction.amount, inputmode: 'decimal' },
      { name: 'dailyLimit', label: 'Daily limit (INR)', required: true, value: c.dailyLimit.amount, inputmode: 'decimal' },
      { name: 'paymentTtlMinutes', label: 'Payment link valid for (minutes)', type: 'number', required: true, min: 1, max: 10080, value: c.paymentTtlMinutes },
      { name: 'maxAttempts', label: 'Attempts per payment', type: 'number', required: true, min: 1, max: 10, value: c.maxAttempts },
      { name: 'refundWindowDays', label: 'Refund window (days)', type: 'number', required: true, min: 1, max: 3650, value: c.refundWindowDays },
      { name: 'notifyPatientEmail', label: 'Email patients about their payments', type: 'checkbox', value: c.notifyPatientEmail },
    ];
  formDialog(webhookOnly ? 'Webhook URL' : 'Payment configuration', spec, async (v) => {
    const m = (amount) => ({ amount: toAmount(amount), currency: c.minAmount.currency });
    const body = {
      enabledMethods: v.enabledMethods ?? c.enabledMethods,
      captureMode: v.captureMode ?? c.captureMode,
      minAmount: v.minAmount ? m(v.minAmount) : c.minAmount,
      maxAmountPerTransaction: v.maxAmountPerTransaction ? m(v.maxAmountPerTransaction) : c.maxAmountPerTransaction,
      dailyLimit: v.dailyLimit ? m(v.dailyLimit) : c.dailyLimit,
      paymentTtlMinutes: Number(v.paymentTtlMinutes ?? c.paymentTtlMinutes),
      maxAttempts: Number(v.maxAttempts ?? c.maxAttempts),
      refundWindowDays: Number(v.refundWindowDays ?? c.refundWindowDays),
      webhookUrl: webhookOnly ? v.webhookUrl : c.webhookUrl,
      notifyPatientEmail: v.notifyPatientEmail ?? c.notifyPatientEmail,
      notifyPatientSms: c.notifyPatientSms,
    };
    if (!body.enabledMethods.length) throw new Error('Select at least one payment method.');
    await api('/api/v1/merchants/me/configuration', { method: 'PUT', body });
    toast('Configuration saved');
    onDone();
  });
}
