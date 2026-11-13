// Operations: reconciliation of the ledger against the provider's settlement reports.
// Reconciliation only detects and reports; resolving a discrepancy never changes money records.
import { api, get, upload, download } from '../api.js';
import {
  h, clear, money, dateTime, relative, humanize, badge, button, spinner, errorBox, table, pager, select, fields,
  card, stat, toast, drawer, formDialog, emptyState,
} from '../ui.js';
import { pageHeader } from '../router.js';

const MATCH_STATUSES = ['MATCHED', 'MISMATCHED', 'MISSING_IN_PROVIDER', 'MISSING_IN_INTERNAL_SYSTEM', 'DUPLICATE', 'PENDING_REVIEW'];
const RESOLUTION_TYPES = [
  ['PROVIDER_ERROR_RAISED', 'Provider error raised (ticket with the provider)'],
  ['INTERNAL_CORRECTION_POSTED', 'Internal correction posted (ledger reversal)'],
  ['TIMING_DIFFERENCE', 'Timing difference (will settle later)'],
  ['ACCEPTED_AS_IS', 'Accepted as is'],
];
const localDate = (d) => new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
const yesterday = () => localDate(new Date(Date.now() - 86400_000));

export async function reconciliation(main) {
  const state = { page: 0, businessDate: '' };
  const results = h('div');
  const reload = async () => {
    clear(results, spinner());
    try {
      const data = await get('/api/v1/reconciliation/batches', { businessDate: state.businessDate, page: state.page, size: 20 });
      clear(results, table([
        { label: 'Business date', value: (b) => h('span', { class: 'strong' }, b.businessDate) },
        { label: 'Source', value: (b) => (b.source === 'FILE' ? b.fileName || 'File' : `${humanize(b.provider)} API`) },
        { label: 'Status', value: (b) => badge(b.status) },
        { label: 'Provider rows', value: (b) => b.providerRows, class: 'num' },
        { label: 'Matched', value: (b) => b.matched, class: 'num' },
        { label: 'Discrepancies', value: (b) => (b.discrepancies ? h('span', { class: 'strong' }, b.discrepancies) : '0'), class: 'num' },
        { label: 'Started', value: (b) => h('span', { title: dateTime(b.startedAt) }, relative(b.startedAt)) },
      ], data.content, { onRowClick: (b) => batchDrawer(b.id, reload),
        empty: 'No reconciliation yet. Upload the provider\'s settlement report to start.' }),
      pager(data.page, (p) => { state.page = p; reload(); }));
    } catch (e) { clear(results, errorBox(e)); }
  };
  const dateFilter = h('input', { type: 'date', 'aria-label': 'Business date', max: localDate(new Date()) });
  dateFilter.addEventListener('change', () => { state.businessDate = dateFilter.value; state.page = 0; reload(); });
  clear(main,
    pageHeader('Reconciliation', 'Our ledger against the provider\'s settlement report. Differences are flagged for review; '
      + 'nothing here changes money records.',
      button('Run from provider API', () => apiRunDialog(reload)),
      button('Upload settlement file', () => uploadDialog(reload), { variant: 'primary' })),
    h('div', { class: 'toolbar' }, h('label', { class: 'inline' }, h('span', { class: 'muted small' }, 'Business date'), dateFilter)),
    results);
  await reload();
}

function uploadDialog(onDone) {
  formDialog('Upload settlement file', [
    { name: 'businessDate', label: 'Business date', type: 'date', required: true, value: yesterday(), max: localDate(new Date()) },
    { name: 'file', label: 'Settlement report (CSV, up to 20 MB)', type: 'file', required: true, accept: '.csv,text/csv',
      hint: 'Columns: entity_id, type, order_id, amount, fee, tax, currency, settlement_id, settled_at, created_at.' },
  ], async (v) => {
    if (!v.file) throw new Error('Choose a CSV file.');
    const form = new FormData();
    form.append('file', v.file, v.file.name);
    const batch = await upload('/api/v1/reconciliation/settlement-files', form, { businessDate: v.businessDate });
    toast(`Reconciling ${batch.businessDate}…`);
    onDone();
    batchDrawer(batch.id, onDone);
  }, { submitLabel: 'Upload and reconcile', intro: 'The same file cannot be loaded twice. Unreadable rows are reported, not fatal.' });
}

function apiRunDialog(onDone) {
  formDialog('Run from provider API', [
    { name: 'businessDate', label: 'Business date', type: 'date', required: true, value: yesterday(), max: localDate(new Date()) },
  ], async (v) => {
    const { data } = await api('/api/v1/reconciliation/runs', { method: 'POST', body: { businessDate: v.businessDate } });
    toast(`Reconciling ${data.businessDate}…`);
    onDone();
    batchDrawer(data.id, onDone);
  }, { submitLabel: 'Run', intro: 'Fetches the provider\'s settlement report for the date. Runs daily at 06:00 automatically where configured.' });
}

function batchDrawer(id, onChange) {
  let timer;
  const ctx = drawer('Reconciliation batch', async (c) => {
    clearTimeout(timer);
    const b = await get(`/api/v1/reconciliation/batches/${id}`);
    if (b.status === 'RUNNING') {
      timer = setTimeout(() => { if (document.body.contains(c.body)) { c.refresh(); onChange?.(); } }, 1500);
      return h('div', { class: 'stack' }, fields([['Business date', b.businessDate], ['Status', badge(b.status)]]),
        spinner('Matching provider rows against the ledger…'));
    }
    const filter = { status: '' };
    const recordsBox = h('div');
    const loadRecords = async (page = 0) => {
      clear(recordsBox, spinner());
      try {
        const data = await get(`/api/v1/reconciliation/batches/${id}/records`, { status: filter.status, page, size: 50 });
        clear(recordsBox, table([
          { label: 'Result', value: (r) => badge(r.status) },
          { label: 'Reference', value: (r) => h('code', {}, r.providerReference || '—') },
          { label: 'Ledger', value: (r) => (r.internalAmount ? money(r.internalAmount) : '—'), class: 'num' },
          { label: 'Provider', value: (r) => (r.providerAmount ? money(r.providerAmount) : '—'), class: 'num' },
          { label: 'Workflow', value: (r) => (r.resolutionStatus ? badge(r.resolutionStatus) : '') },
        ], data.content, { onRowClick: (r) => recordDrawer(r.id, () => loadRecords(page)), empty: 'No records with this result.' }),
        pager(data.page, (p) => loadRecords(p)));
      } catch (e) { clear(recordsBox, errorBox(e)); }
    };
    loadRecords();
    return h('div', { class: 'stack' },
      h('div', { class: 'detail-hero' }, h('p', { class: 'hero-amount small-hero' }, b.businessDate), badge(b.status),
        h('span', { class: 'muted' }, b.source === 'FILE' ? b.fileName : `${humanize(b.provider)} API`)),
      b.status === 'FAILED' && h('div', { class: 'alert alert-error' }, b.failureReason || 'The run failed.'),
      b.status === 'COMPLETED' && h('div', { class: 'stats stats-compact' },
        stat('Matched', b.matched), stat('Mismatched', b.mismatched), stat('Missing at provider', b.missingInProvider),
        stat('Missing in ledger', b.missingInInternalSystem), stat('Duplicate', b.duplicate), stat('Needs review', b.pendingReview)),
      fields([
        ['Provider rows', `${b.providerRows}${b.malformedRows ? ` (${b.malformedRows} unreadable)` : ''}`],
        ['Started', `${dateTime(b.startedAt)} by ${b.startedBy || '—'}`],
        ['Completed', b.completedAt ? dateTime(b.completedAt) : null],
      ]),
      b.status === 'COMPLETED' && h('div', { class: 'actions' }, button('Download daily report (CSV)', async () => {
        try {
          await download('/api/v1/reconciliation/reports/daily', { date: b.businessDate }, 'text/csv', `reconciliation-${b.businessDate}.csv`);
        } catch (e) { toast(e.message, 'bad'); }
      })),
      b.status === 'COMPLETED' && [h('div', { class: 'toolbar' }, h('h3', {}, 'Records'),
        select('status', [['', 'All results'], ...MATCH_STATUSES.map((s) => [s, humanize(s)])], '', (v) => { filter.status = v; loadRecords(); }, 'Result')),
      recordsBox]);
  });
  return ctx;
}

function recordDrawer(id, onChange) {
  drawer('Reconciliation record', async (ctx) => {
    const r = await get(`/api/v1/reconciliation/records/${id}`);
    const after = () => { ctx.refresh(); onChange?.(); };
    const actions = [];
    if (r.resolutionStatus === 'OPEN') {
      actions.push(button('Start review', async () => {
        try { await api(`/api/v1/reconciliation/records/${id}/review`, { method: 'POST' }); toast('In review'); after(); } catch (e) { toast(e.message, 'bad'); }
      }));
    }
    if (r.resolutionStatus === 'OPEN' || r.resolutionStatus === 'IN_REVIEW') {
      actions.push(button('Resolve', () => formDialog('Resolve discrepancy', [
        { name: 'resolutionType', label: 'Resolution', type: 'select', required: true, options: RESOLUTION_TYPES, value: 'PROVIDER_ERROR_RAISED' },
        { name: 'note', label: 'Note (written to the record)', type: 'textarea', required: true, maxlength: 1000 },
        { name: 'correctionReference', label: 'Correction transaction reference', maxlength: 64,
          hint: 'Required for "Internal correction posted": the reference of the ledger correction you posted.' },
      ], async (v) => {
        await api(`/api/v1/reconciliation/records/${id}/resolve`, { method: 'POST', body: v });
        toast('Discrepancy resolved');
        after();
      }, { submitLabel: 'Resolve', intro: 'This updates the reconciliation record only. Post any ledger correction separately first.' }),
      { variant: 'primary' }));
    }
    return h('div', { class: 'stack' },
      h('div', { class: 'detail-hero' }, badge(r.status), r.resolutionStatus && badge(r.resolutionStatus)),
      r.detail && h('div', { class: `alert ${r.status === 'MATCHED' ? 'alert-good' : 'alert-warn'}` }, r.detail),
      actions.length > 0 && h('div', { class: 'actions' }, actions),
      fields([
        ['Type', humanize(r.entityType)],
        ['Provider reference', r.providerReference ? h('code', {}, r.providerReference) : null],
        ['Ledger transaction', r.internalTransactionRef ? h('code', {}, r.internalTransactionRef) : null],
        ['Hospital', r.merchantId ? h('code', {}, r.merchantId) : null],
        ['Ledger amount', r.internalAmount ? money(r.internalAmount) : null],
        ['Provider amount', r.providerAmount ? money(r.providerAmount) : null],
      ]),
      r.resolutionStatus === 'RESOLVED' && card('Resolution', fields([
        ['Resolution', humanize(r.resolutionType)],
        ['Note', r.resolutionNote],
        ['Correction reference', r.correctionReference],
        ['Resolved by', r.resolvedBy],
        ['Resolved at', dateTime(r.resolvedAt)],
      ])),
      r.status === 'MATCHED' && emptyState('Nothing to resolve', 'Both sides agree on this item.'));
  });
}
