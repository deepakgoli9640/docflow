// Shell: sign-in screen, navigation for the two audiences, hash router.
import { auth } from './api.js';
import { h, clear, errorBox, button } from './ui.js';
import * as hospital from './views/hospital.js';
import * as admin from './views/admin.js';
import { reconciliation } from './views/reconciliation.js';

const root = document.getElementById('app');

const NAV = {
  MERCHANT: [
    ['overview', 'Overview', hospital.overview],
    ['payments', 'Payments', hospital.payments],
    ['refunds', 'Refunds', hospital.refunds],
    ['transactions', 'Transactions', hospital.transactions],
    ['notifications', 'Notifications', hospital.notifications],
    ['settings', 'Settings', hospital.settings],
  ],
  OPS: [
    ['overview', 'Overview', admin.overview],
    ['merchants', 'Hospitals', admin.merchants],
    ['notifications', 'Notifications', admin.notifications],
    ['reconciliation', 'Reconciliation', reconciliation],
    ['ledger', 'Ledger', admin.ledger],
  ],
};

function route() {
  const [path, query] = location.hash.replace(/^#\/?/, '').split('?');
  return { page: path || 'overview', params: new URLSearchParams(query || '') };
}

function render() {
  const s = auth.session;
  if (!s) return renderSignIn();
  const items = NAV[s.role];
  const { page, params } = route();
  const current = items.find(([id]) => id === page) || items[0];
  const main = h('main', { class: 'main', id: 'main', tabindex: '-1' });
  clear(root,
    h('a', { class: 'skip', href: '#main' }, 'Skip to content'),
    h('div', { class: 'shell' },
      h('nav', { class: 'sidebar', 'aria-label': 'Main' },
        h('div', { class: 'brand' }, h('span', { class: 'brand-mark', 'aria-hidden': 'true' }, 'H'),
          h('div', {}, h('p', { class: 'brand-name' }, 'HPG Dashboard'),
            h('p', { class: 'brand-sub' }, s.role === 'OPS' ? 'Operations' : 'Hospital'))),
        h('ul', { class: 'nav' }, items.map(([id, label]) => h('li', {},
          h('a', { href: `#/${id}`, class: id === current[0] ? 'active' : undefined,
            'aria-current': id === current[0] ? 'page' : undefined }, label)))),
        h('div', { class: 'sidebar-foot' },
          h('p', { class: 'muted small', title: s.clientId }, `Signed in as ${s.clientId}`),
          button('Sign out', () => auth.signOut(), { variant: 'ghost' }))),
      main));
  Promise.resolve(current[2](main, params)).catch((e) => clear(main, errorBox(e)));
}

function renderSignIn() {
  let role = 'MERCHANT';
  const notice = sessionStorage.getItem('hpg.signout');
  sessionStorage.removeItem('hpg.signout');
  const errors = h('div', { 'aria-live': 'polite' });
  const idInput = h('input', { id: 'client-id', name: 'clientId', required: true, autocomplete: 'username', spellcheck: 'false' });
  const secretInput = h('input', { id: 'client-secret', name: 'clientSecret', type: 'password', required: true, autocomplete: 'current-password' });
  const idLabel = h('label', { for: 'client-id' }, 'API key ID');
  const hint = h('p', { class: 'hint' });
  const tabs = h('div', { class: 'tabs', role: 'tablist' });

  const setRole = (r) => {
    role = r;
    idLabel.textContent = r === 'OPS' ? 'Client ID' : 'API key ID';
    idInput.value = r === 'OPS' ? 'ops-console' : '';
    hint.textContent = r === 'OPS'
      ? 'Use the ops-console client secret (HPG_OPS_CLIENT_SECRET in deploy/env/.env).'
      : 'Use an API key of your hospital: the key ID and secret shown when the key was created.';
    clear(tabs, ...[['MERCHANT', 'Hospital'], ['OPS', 'Operations']].map(([v, t]) =>
      h('button', { type: 'button', role: 'tab', class: v === r ? 'tab active' : 'tab', 'aria-selected': String(v === r),
        onClick: () => setRole(v) }, t)));
  };
  setRole(role);

  const form = h('form', { class: 'form' },
    tabs,
    h('div', { class: 'field' }, idLabel, idInput),
    h('div', { class: 'field' }, h('label', { for: 'client-secret' }, 'Secret'), secretInput, hint),
    errors,
    button('Sign in', null, { variant: 'primary', type: 'submit' }));
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const submit = form.querySelector('button[type=submit]');
    submit.disabled = true;
    clear(errors);
    try {
      await auth.signIn(idInput.value, secretInput.value, role);
      location.hash = '#/overview';
    } catch (err) {
      clear(errors, errorBox(err));
      submit.disabled = false;
    }
  });
  clear(root, h('main', { class: 'signin' },
    h('div', { class: 'signin-card' },
      h('div', { class: 'brand' }, h('span', { class: 'brand-mark', 'aria-hidden': 'true' }, 'H'),
        h('div', {}, h('p', { class: 'brand-name' }, 'HPG Dashboard'), h('p', { class: 'brand-sub' }, 'Healthcare Payment Gateway'))),
      h('h1', {}, 'Sign in'),
      notice && h('div', { class: 'alert' }, notice),
      form,
      h('p', { class: 'muted small' }, 'Your secret is sent only to the gateway token endpoint and kept in memory, never stored.'))));
  idInput.value ? secretInput.focus() : idInput.focus();
}

auth.onChange(() => render());
window.addEventListener('hashchange', () => render());
render();
