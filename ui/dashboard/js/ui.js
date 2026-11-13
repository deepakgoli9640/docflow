// Small DOM toolkit. Every piece of data is inserted as text (never as HTML), so values coming from the
// API cannot inject markup or script.

export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, String(v));
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c === undefined || c === null || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

export function clear(el, ...children) {
  el.replaceChildren();
  append(el, children);
  return el;
}

// ---------- formatting
const moneyFormats = new Map();
export function money(m) {
  if (!m) return '—';
  const key = m.currency;
  if (!moneyFormats.has(key)) {
    moneyFormats.set(key, new Intl.NumberFormat(undefined, { style: 'currency', currency: key }));
  }
  return moneyFormats.get(key).format(Number(m.amount));
}

const dateFmt = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });
export const dateTime = (iso) => (iso ? dateFmt.format(new Date(iso)) : '—');

export function relative(iso) {
  if (!iso) return '—';
  const s = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
  const abs = Math.abs(s);
  if (abs < 60) return rtf.format(-s, 'second');
  if (abs < 3600) return rtf.format(-Math.round(s / 60), 'minute');
  if (abs < 86400) return rtf.format(-Math.round(s / 3600), 'hour');
  return rtf.format(-Math.round(s / 86400), 'day');
}

export const shortId = (id) => (id ? String(id).slice(-8) : '—');
const ACRONYMS = /\b(api|upi|url|http|id|ops|emi)\b/gi;
const LABELS = { NETBANKING: 'Net banking' };
export const humanize = (s) => {
  if (!s) return '—';
  if (LABELS[s]) return LABELS[s];
  return String(s).replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase())
    .replace(ACRONYMS, (w) => w.toUpperCase());
};

// ---------- status badges
const TONES = {
  good: ['CAPTURED', 'COMPLETED', 'DELIVERED', 'ACTIVE', 'APPLIED', 'MATCHED', 'RESOLVED', 'WON'],
  warn: ['PENDING', 'PROCESSING', 'AUTHORIZED', 'UNKNOWN', 'CREATED', 'INITIATED', 'PENDING_ACTIVATION', 'RETRY',
    'RUNNING', 'OPEN', 'IN_REVIEW', 'UNDER_REVIEW', 'DUPLICATE', 'PENDING_REVIEW'],
  bad: ['FAILED', 'DEAD', 'SUSPENDED', 'DEACTIVATED', 'EXPIRED', 'MISMATCHED', 'MISSING_IN_PROVIDER',
    'MISSING_IN_INTERNAL_SYSTEM', 'LOST'],
  muted: ['CANCELLED', 'SKIPPED', 'REVOKED', 'CLOSED'],
};
export function badge(status) {
  const tone = Object.entries(TONES).find(([, list]) => list.includes(status))?.[0] || 'muted';
  return h('span', { class: `badge badge-${tone}` }, humanize(status));
}

// ---------- components
export function button(label, onClick, { variant = 'secondary', type = 'button', disabled, title } = {}) {
  return h('button', { class: `btn btn-${variant}`, type, onClick, disabled, title }, label);
}

export function spinner(label = 'Loading…') {
  return h('div', { class: 'loading', role: 'status' }, h('span', { class: 'spinner', 'aria-hidden': 'true' }), label);
}

export function emptyState(title, text) {
  return h('div', { class: 'empty' }, h('p', { class: 'empty-title' }, title), text && h('p', {}, text));
}

export function errorBox(err) {
  const p = err?.problem || {};
  return h('div', { class: 'alert alert-error', role: 'alert' },
    h('strong', {}, p.title || 'Something went wrong'),
    h('p', {}, err?.message || String(err)),
    (p.errorCode || p.correlationId) && h('p', { class: 'muted small' },
      p.errorCode && `Code ${p.errorCode}`, p.errorCode && p.correlationId && ' · ',
      p.correlationId && `Reference ${p.correlationId}`),
    p.violations?.length > 0 && h('ul', {}, p.violations.map((v) => h('li', {}, `${v.field}: ${v.message}`))));
}

/**
 * A data table.
 * @param {Array<{label: string, value: (row) => any, class?: string}>} columns
 */
export function table(columns, rows, { onRowClick, empty = 'Nothing here yet.' } = {}) {
  if (!rows.length) return emptyState(empty);
  return h('div', { class: 'table-wrap' }, h('table', { class: 'table' },
    h('thead', {}, h('tr', {}, columns.map((c) => h('th', { class: c.class, scope: 'col' }, c.label)))),
    h('tbody', {}, rows.map((r) => {
      const tr = h('tr', { class: onRowClick ? 'clickable' : undefined, tabindex: onRowClick ? 0 : undefined },
        columns.map((c) => h('td', { class: c.class, 'data-label': c.label }, c.value(r))));
      if (onRowClick) {
        tr.addEventListener('click', () => onRowClick(r));
        tr.addEventListener('keydown', (e) => { if (e.key === 'Enter') onRowClick(r); });
      }
      return tr;
    }))));
}

export function pager(page, onPage) {
  if (!page || page.totalPages <= 1) {
    return page ? h('p', { class: 'pager muted small' }, `${page.totalElements} result${page.totalElements === 1 ? '' : 's'}`) : null;
  }
  return h('div', { class: 'pager' },
    button('Previous', () => onPage(page.number - 1), { disabled: page.number === 0 }),
    h('span', { class: 'muted small' }, `Page ${page.number + 1} of ${page.totalPages} · ${page.totalElements} results`),
    button('Next', () => onPage(page.number + 1), { disabled: page.number + 1 >= page.totalPages }));
}

export function select(name, options, value, onChange, label) {
  const el = h('select', { name, 'aria-label': label || name, onChange: (e) => onChange(e.target.value) },
    options.map(([v, text]) => h('option', { value: v, selected: v === value }, text)));
  return el;
}

/** Definition list of label/value pairs. */
export function fields(pairs) {
  return h('dl', { class: 'fields' }, pairs.filter(Boolean).map(([k, v]) => [h('dt', {}, k), h('dd', {}, v ?? '—')]));
}

export function card(title, ...children) {
  return h('section', { class: 'card' }, title && h('h2', { class: 'card-title' }, title), ...children);
}

export function stat(label, value, hint) {
  return h('div', { class: 'stat' }, h('p', { class: 'stat-label' }, label), h('p', { class: 'stat-value' }, value),
    hint && h('p', { class: 'stat-hint' }, hint));
}

export function timeline(items) {
  if (!items?.length) return emptyState('No history yet.');
  return h('ol', { class: 'timeline' }, items.map((i) => h('li', {},
    h('div', { class: 'timeline-head' }, badge(i.status), h('span', { class: 'muted small' }, dateTime(i.at))),
    i.text && h('p', { class: 'small' }, i.text))));
}

export function copyButton(text, label = 'Copy') {
  const b = button(label, async () => {
    try {
      await navigator.clipboard.writeText(text);
      b.textContent = 'Copied';
    } catch {
      b.textContent = 'Copy failed';
    }
    setTimeout(() => { b.textContent = label; }, 1500);
  }, { variant: 'ghost' });
  return b;
}

// ---------- toasts
export function toast(message, tone = 'good') {
  let region = document.getElementById('toasts');
  if (!region) {
    region = h('div', { id: 'toasts', class: 'toasts', 'aria-live': 'polite' });
    document.body.append(region);
  }
  const t = h('div', { class: `toast toast-${tone}` }, message);
  region.append(t);
  setTimeout(() => t.remove(), 4500);
}

// ---------- drawer (details) and dialog (forms)
export function drawer(title, build) {
  const close = () => { overlay.remove(); document.removeEventListener('keydown', onKey); };
  const onKey = (e) => { if (e.key === 'Escape') close(); };
  const body = h('div', { class: 'drawer-body' });
  const panel = h('aside', { class: 'drawer', role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
    h('header', { class: 'drawer-header' }, h('h2', {}, title), button('Close', close, { variant: 'ghost' })),
    body);
  const overlay = h('div', { class: 'overlay', onClick: (e) => { if (e.target === overlay) close(); } }, panel);
  document.body.append(overlay);
  document.addEventListener('keydown', onKey);
  const ctx = { body, close, refresh: () => run() };
  const run = async () => {
    clear(body, spinner());
    try { clear(body, await build(ctx)); } catch (e) { clear(body, errorBox(e)); }
  };
  run();
  panel.querySelector('button').focus();
  return ctx;
}

/**
 * A modal form. {@code fieldsSpec}: [{name, label, type, required, value, options, hint, min, step, placeholder}]
 * {@code onSubmit(values)} returns either nothing (dialog closes) or a Node to show as the result.
 */
export function formDialog(title, fieldsSpec, onSubmit, { submitLabel = 'Save', intro, danger } = {}) {
  const close = () => { overlay.remove(); document.removeEventListener('keydown', onKey); };
  const onKey = (e) => { if (e.key === 'Escape') close(); };
  const errors = h('div');
  const form = h('form', { class: 'form', novalidate: true },
    intro && h('p', { class: 'muted' }, intro),
    fieldsSpec.map((f) => fieldInput(f)),
    errors,
    h('div', { class: 'form-actions' },
      button('Cancel', close, { variant: 'ghost' }),
      button(submitLabel, null, { variant: danger ? 'danger' : 'primary', type: 'submit' })));
  const content = h('div', { class: 'dialog-body' }, form);
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!form.reportValidity()) return;
    const submit = form.querySelector('button[type=submit]');
    submit.disabled = true;
    clear(errors);
    try {
      const values = readForm(form, fieldsSpec);
      const result = await onSubmit(values);
      if (result instanceof Node) {
        clear(content, result, h('div', { class: 'form-actions' }, button('Done', close, { variant: 'primary' })));
      } else {
        close();
      }
    } catch (err) {
      clear(errors, errorBox(err));
      submit.disabled = false;
    }
  });
  const dialog = h('div', { class: 'dialog', role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
    h('header', { class: 'drawer-header' }, h('h2', {}, title)), content);
  const overlay = h('div', { class: 'overlay overlay-center', onClick: (e) => { if (e.target === overlay) close(); } }, dialog);
  document.body.append(overlay);
  document.addEventListener('keydown', onKey);
  form.querySelector('input,select,textarea')?.focus();
}

function fieldInput(f) {
  const id = `f-${f.name}`;
  let input;
  if (f.type === 'select') {
    input = h('select', { id, name: f.name, required: f.required },
      f.options.map(([v, t]) => h('option', { value: v, selected: v === f.value }, t)));
  } else if (f.type === 'checkboxes') {
    return h('fieldset', { class: 'field' }, h('legend', {}, f.label),
      h('div', { class: 'checks' }, f.options.map(([v, t]) => h('label', { class: 'check' },
        h('input', { type: 'checkbox', name: f.name, value: v, checked: (f.value || []).includes(v) }), t))),
      f.hint && h('p', { class: 'hint' }, f.hint));
  } else if (f.type === 'checkbox') {
    return h('label', { class: 'check field' }, h('input', { type: 'checkbox', name: f.name, checked: !!f.value }), f.label);
  } else if (f.type === 'textarea') {
    input = h('textarea', { id, name: f.name, required: f.required, rows: 3, maxlength: f.maxlength }, f.value || '');
  } else {
    input = h('input', { id, name: f.name, type: f.type || 'text', required: f.required, value: f.value ?? '',
      min: f.min, max: f.max, step: f.step, placeholder: f.placeholder, autocomplete: f.autocomplete || 'off',
      maxlength: f.maxlength, pattern: f.pattern, inputmode: f.inputmode, accept: f.accept });
  }
  return h('div', { class: 'field' }, h('label', { for: id }, f.label, f.required ? '' : h('span', { class: 'muted' }, ' (optional)')),
    input, f.hint && h('p', { class: 'hint' }, f.hint));
}

function readForm(form, spec) {
  const values = {};
  for (const f of spec) {
    if (f.type === 'file') {
      values[f.name] = form.querySelector(`input[name="${f.name}"]`).files[0] || null;
    } else if (f.type === 'checkboxes') {
      values[f.name] = [...form.querySelectorAll(`input[name="${f.name}"]:checked`)].map((i) => i.value);
    } else if (f.type === 'checkbox') {
      values[f.name] = form.querySelector(`input[name="${f.name}"]`).checked;
    } else {
      const v = form.elements[f.name].value.trim();
      values[f.name] = v === '' ? null : v;
    }
  }
  return values;
}

export function confirmDialog(title, message, onConfirm, { confirmLabel = 'Confirm', danger, reason } = {}) {
  const spec = reason ? [{ name: 'reason', label: reason, type: 'textarea', required: true, maxlength: 500 }] : [];
  formDialog(title, spec, onConfirm, { submitLabel: confirmLabel, intro: message, danger });
}

/** Two-decimal amount string from user input, e.g. "1500" -> "1500.00". */
export function toAmount(value) {
  const v = String(value ?? '').trim().replace(/,/g, '');
  if (!/^\d+(\.\d{1,2})?$/.test(v)) throw new Error('Enter an amount like 1500 or 1500.50 (at most 2 decimals).');
  if (Number(v) <= 0) throw new Error('Enter an amount greater than zero.');
  return Number(v).toFixed(2);
}
