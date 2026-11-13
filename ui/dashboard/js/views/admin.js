// Operations (OPS role) views: hospitals, failed notifications, ledger health.
import { api, get } from '../api.js';
import {
  h, clear, money, dateTime, relative, shortId, humanize, badge, button, spinner, errorBox, table, pager, select,
  fields, card, stat, toast, drawer, confirmDialog, emptyState,
} from '../ui.js';
import { navigate, pageHeader } from '../router.js';

const MERCHANT_STATUSES = ['PENDING_ACTIVATION', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED'];

// ============================================================ Overview
export async function overview(main) {
  clear(main, pageHeader('Operations overview', 'Hospitals, delivery failures and ledger health at a glance.'), spinner());
  const count = (path, query) => get(path, { ...query, size: 1 }).then((p) => p.page.totalElements).catch(() => '—');
  const [active, pending, suspended, dead, integrity, pendingList] = await Promise.all([
    count('/api/v1/admin/merchants', { status: 'ACTIVE' }),
    count('/api/v1/admin/merchants', { status: 'PENDING_ACTIVATION' }),
    count('/api/v1/admin/merchants', { status: 'SUSPENDED' }),
    count('/api/v1/admin/notifications', { status: 'DEAD' }),
    get('/api/v1/admin/ledger/integrity').catch((e) => e),
    get('/api/v1/admin/merchants', { status: 'PENDING_ACTIVATION', size: 5 }).catch(() => ({ content: [] })),
  ]);
  clear(main,
    pageHeader('Operations overview', 'Hospitals, delivery failures and ledger health at a glance.'),
    h('div', { class: 'stats' },
      stat('Active hospitals', active),
      stat('Awaiting activation', pending, pending ? h('a', { href: '#/merchants?status=PENDING_ACTIVATION' }, 'Review →') : null),
      stat('Suspended', suspended),
      stat('Failed notifications', dead, dead ? h('a', { href: '#/notifications' }, 'Review →') : 'None'),
      stat('Ledger', integrity instanceof Error ? 'Unavailable' : integrity.healthy ? 'Healthy' : 'Check needed',
        h('a', { href: '#/ledger' }, 'Details →'))),
    integrity instanceof Error ? errorBox(integrity)
      : !integrity.healthy && h('div', { class: 'alert alert-error' }, h('strong', {}, 'Ledger integrity check failed'),
        h('p', {}, 'Open the Ledger page for the failing checks.')),
    card('Waiting for activation', merchantsTable(pendingList.content, 'No hospitals are waiting.')));
}

// ============================================================ Hospitals
function merchantsTable(rows, empty = 'No hospitals match.') {
  return table([
    { label: 'Hospital', value: (m) => h('span', { class: 'strong' }, m.displayName) },
    { label: 'Legal name', value: (m) => m.legalName },
    { label: 'Status', value: (m) => badge(m.status) },
    { label: 'Contact', value: (m) => m.contactEmail },
    { label: 'Registered', value: (m) => h('span', { title: dateTime(m.createdAt) }, relative(m.createdAt)) },
  ], rows, { onRowClick: (m) => merchantDrawer(m.id), empty });
}

export async function merchants(main, params) {
  const state = { status: params.get('status') || '', page: 0 };
  const results = h('div');
  const reload = async () => {
    clear(results, spinner());
    try {
      const data = await get('/api/v1/admin/merchants', { status: state.status, page: state.page, size: 20 });
      clear(results, merchantsTable(data.content), pager(data.page, (p) => { state.page = p; reload(); }));
    } catch (e) { clear(results, errorBox(e)); }
  };
  clear(main, pageHeader('Hospitals', 'Onboarding, activation and suspension.'),
    h('div', { class: 'toolbar' }, select('status', [['', 'All statuses'], ...MERCHANT_STATUSES.map((s) => [s, humanize(s)])],
      state.status, (v) => { state.status = v; state.page = 0; reload(); }, 'Status')),
    results);
  if (params.get('id')) merchantDrawer(params.get('id'), reload);
  await reload();
}

function merchantDrawer(id, onChange) {
  drawer('Hospital', async (ctx) => {
    const [m, config, balance] = await Promise.all([
      get(`/api/v1/admin/merchants/${id}`),
      get(`/api/v1/admin/merchants/${id}/configuration`).catch(() => null),
      get(`/api/v1/admin/ledger/accounts/${encodeURIComponent(`MERCHANT_PAYABLE:${id}`)}/balance`).catch(() => []),
    ]);
    const changeStatus = (action, label, message, danger) => button(label, () => confirmDialog(`${label}: ${m.displayName}`, message,
      async (v) => {
        const { data } = await api(`/api/v1/admin/merchants/${id}/${action}`, { method: 'POST', body: { reason: v.reason } });
        toast(`${data.displayName} is now ${humanize(data.status).toLowerCase()}`);
        ctx.refresh(); onChange?.();
      }, { confirmLabel: label, danger, reason: 'Reason (written to the audit log)' }),
    { variant: danger ? 'danger' : 'primary' });

    const actions = [];
    if (['PENDING_ACTIVATION', 'SUSPENDED'].includes(m.status)) {
      actions.push(changeStatus('activate', 'Activate', 'The hospital can sign in and take payments again.', false));
    }
    if (m.status === 'ACTIVE') {
      actions.push(changeStatus('suspend', 'Suspend', 'New sign-ins are refused immediately, and existing sessions are blocked within seconds.', true));
    }
    if (m.status !== 'DEACTIVATED') {
      actions.push(changeStatus('deactivate', 'Deactivate', 'Permanent. The hospital can never be reactivated.', true));
    }

    return h('div', { class: 'stack' },
      h('div', { class: 'detail-hero' }, h('p', { class: 'hero-amount small-hero' }, m.displayName), badge(m.status)),
      h('div', { class: 'actions' }, actions),
      fields([
        ['Legal name', m.legalName],
        ['Merchant ID', h('code', {}, m.id)],
        ['Contact', [m.contactEmail, m.contactPhone].filter(Boolean).join(' · ')],
        m.statusReason && ['Status reason', m.statusReason],
        ['Registered', dateTime(m.createdAt)],
        ['Owed to hospital', balance.length ? balance.map((b) => money(b.balance)).join(', ') : money({ amount: '0', currency: 'INR' })],
      ]),
      h('h3', {}, 'Payment configuration'),
      config ? fields([
        ['Methods', config.enabledMethods.map(humanize).join(', ')],
        ['Capture', humanize(config.captureMode)],
        ['Per payment', `${money(config.minAmount)} – ${money(config.maxAmountPerTransaction)}`],
        ['Daily limit', money(config.dailyLimit)],
        ['Webhook URL', config.webhookUrl || 'Not configured'],
        ['Last changed', dateTime(config.updatedAt)],
      ]) : emptyState('Configuration unavailable.'),
      h('p', { class: 'muted small' }, 'Payment processing, refunds and the gateway receive every change to this hospital '
        + 'automatically within seconds.'),
      h('p', {}, h('a', { href: `#/notifications?merchantId=${id}`, onClick: () => ctx.close() }, 'Notifications for this hospital →')));
  });
}

// ============================================================ Notifications
export async function notifications(main, params) {
  const state = { status: params.has('status') ? params.get('status') : 'DEAD', channel: '', merchantId: params.get('merchantId') || '', page: 0 };
  const results = h('div');
  const reload = async () => {
    clear(results, spinner());
    try {
      const data = await get('/api/v1/admin/notifications', { status: state.status, channel: state.channel,
        merchantId: state.merchantId, page: state.page, size: 20 });
      clear(results, table([
        { label: 'Event', value: (n) => n.eventType },
        { label: 'Channel', value: (n) => humanize(n.channel) },
        { label: 'Hospital', value: (n) => h('a', { href: `#/merchants?id=${n.merchantId}` }, h('code', {}, shortId(n.merchantId))) },
        { label: 'Status', value: (n) => badge(n.status) },
        { label: 'Attempts', value: (n) => n.attempts, class: 'num' },
        { label: 'Last error', value: (n) => h('span', { class: 'clip', title: n.lastError || '' }, n.lastError || '—') },
        { label: 'Created', value: (n) => relative(n.createdAt) },
        { label: '', value: (n) => (n.status === 'DEAD' ? button('Re-send', async (e) => {
          e.stopPropagation();
          e.target.disabled = true;
          try {
            await api(`/api/v1/admin/notifications/${n.id}/retry`, { method: 'POST' });
            toast('Queued for delivery'); reload();
          } catch (err) { toast(err.message, 'bad'); e.target.disabled = false; }
        }) : '') },
      ], data.content, { onRowClick: (n) => opsNotificationDrawer(n.id, reload),
        empty: state.status === 'DEAD' ? 'No failed notifications. Everything was delivered.' : 'No notifications match.' }),
      pager(data.page, (p) => { state.page = p; reload(); }));
    } catch (e) { clear(results, errorBox(e)); }
  };
  const set = (k) => (v) => { state[k] = v; state.page = 0; reload(); };
  clear(main, pageHeader('Notifications', 'Deliveries across all hospitals. Failed (dead) ones can be re-sent once the cause is fixed.'),
    h('div', { class: 'toolbar' },
      select('status', [['', 'All statuses'], ...['DEAD', 'PENDING', 'DELIVERED', 'SKIPPED'].map((s) => [s, humanize(s)])], state.status, set('status'), 'Status'),
      select('channel', [['', 'All channels'], ['WEBHOOK', 'Webhook'], ['EMAIL', 'Email']], state.channel, set('channel'), 'Channel'),
      state.merchantId && h('span', { class: 'chip' }, `Hospital ${shortId(state.merchantId)}`,
        button('×', () => navigate('notifications'), { variant: 'ghost', title: 'Clear filter' }))),
    results);
  await reload();
}

function opsNotificationDrawer(id, onChange) {
  drawer('Notification', async (ctx) => {
    const n = await get(`/api/v1/admin/notifications/${id}`);
    return h('div', { class: 'stack' },
      h('div', { class: 'detail-hero' }, h('p', { class: 'hero-amount small-hero' }, n.eventType), badge(n.status)),
      n.status === 'DEAD' && h('div', { class: 'actions' }, button('Re-send', async () => {
        await api(`/api/v1/admin/notifications/${id}/retry`, { method: 'POST' });
        toast('Queued for delivery'); ctx.refresh(); onChange?.();
      }, { variant: 'primary' })),
      fields([
        ['Channel', humanize(n.channel)],
        n.recipient && ['Recipient', n.recipient],
        ['Hospital', h('code', {}, n.merchantId)],
        ['Payment', h('code', {}, n.paymentId)],
        ['Event ID', h('code', {}, n.eventId)],
        ['Attempts', n.attempts],
        ['Last response', n.lastResponseStatus ? `HTTP ${n.lastResponseStatus}` : null],
        ['Last error', n.lastError],
      ]),
      h('h3', {}, 'Delivery attempts'),
      n.attemptLog?.length ? table([
        { label: '#', value: (a) => a.attemptNo, class: 'num' },
        { label: 'Outcome', value: (a) => badge(a.outcome) },
        { label: 'HTTP', value: (a) => a.httpStatus ?? '—' },
        { label: 'Error', value: (a) => a.error || '—' },
        { label: 'At', value: (a) => dateTime(a.attemptedAt) },
      ], n.attemptLog) : emptyState('Not attempted yet.'));
  });
}

// ============================================================ Ledger
export async function ledger(main) {
  const integrityBox = h('div');
  const balanceBox = h('div');
  const runIntegrity = async () => {
    clear(integrityBox, spinner('Checking the ledger…'));
    try {
      const r = await get('/api/v1/admin/ledger/integrity');
      const check = (ok, label, detail) => h('li', { class: ok ? 'check-ok' : 'check-bad' },
        h('span', { class: 'check-mark', 'aria-hidden': 'true' }, ok ? '✓' : '✕'),
        h('span', {}, label, h('span', { class: 'muted small' }, ` — ${detail}`)));
      clear(integrityBox,
        h('div', { class: `alert ${r.healthy ? 'alert-good' : 'alert-error'}` }, r.healthy
          ? 'All checks passed. Every posting balances and every captured payment and completed refund is booked.'
          : 'One or more checks failed. Investigate before month-end close.'),
        h('ul', { class: 'checklist' },
          check(r.unbalancedPostings === 0, 'Every posting balances', `${r.unbalancedPostings} unbalanced`),
          check(r.debitsEqualCredits, 'Total debits equal total credits', r.debitsEqualCredits ? 'equal' : 'not equal'),
          check(r.settledTransactionsWithoutPosting === 0, 'Every capture and completed refund is posted',
            `${r.settledTransactionsWithoutPosting} missing`),
          check(r.negativeMerchantPayables === 0, 'No hospital balance is negative', `${r.negativeMerchantPayables} negative`)),
        h('p', { class: 'muted small' }, `Checked ${dateTime(new Date().toISOString())}`));
    } catch (e) { clear(integrityBox, errorBox(e)); }
  };

  const merchantsList = await get('/api/v1/admin/merchants', { size: 100, sort: 'legalName,asc' }).catch(() => ({ content: [] }));
  const accountSelect = select('account', [['PROVIDER_CLEARING', 'Provider clearing (held by the provider)'],
    ...merchantsList.content.map((m) => [`MERCHANT_PAYABLE:${m.id}`, `Owed to ${m.displayName}`])],
  'PROVIDER_CLEARING', (v) => loadBalance(v), 'Account');
  const loadBalance = async (code) => {
    clear(balanceBox, spinner());
    try {
      const rows = await get(`/api/v1/admin/ledger/accounts/${encodeURIComponent(code)}/balance`);
      clear(balanceBox, rows.length ? table([
        { label: 'Currency', value: (b) => b.currency },
        { label: 'Debits', value: (b) => money(b.debits), class: 'num' },
        { label: 'Credits', value: (b) => money(b.credits), class: 'num' },
        { label: 'Balance', value: (b) => h('strong', {}, money(b.balance)), class: 'num' },
      ], rows) : emptyState('No entries on this account yet.'));
    } catch (e) { clear(balanceBox, errorBox(e)); }
  };

  clear(main,
    pageHeader('Ledger', 'Double-entry ledger built from captured payments and completed refunds. Entries are never changed or deleted.',
      button('Run integrity check', runIntegrity, { variant: 'primary' })),
    card('Integrity', integrityBox),
    card('Account balance', h('div', { class: 'toolbar' }, accountSelect), balanceBox,
      h('p', { class: 'muted small' }, 'Hospital accounts are credit-normal: the balance is what the platform owes the hospital.')));
  runIntegrity();
  loadBalance('PROVIDER_CLEARING');
}
