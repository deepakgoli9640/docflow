// API client + session. Everything goes to the same origin; nginx forwards /api, /oauth2 and /checkout
// to the API gateway, so the browser never needs CORS and the gateway enforces every rule as usual.

import { beginLogin, completeLogin, endSession, refresh as oidcRefresh } from './oidc.js';

const TOKEN_KEY = 'hpg.session';

/** Error carrying the RFC 9457 Problem Details body the services return. */
export class ApiError extends Error {
  constructor(status, problem) {
    super(problem?.detail || problem?.title || `HTTP ${status}`);
    this.status = status;
    this.problem = problem || {};
  }
  get code() { return this.problem.errorCode; }
  get correlationId() { return this.problem.correlationId; }
}

// The client secret is kept in memory only (never in storage), so an expired token can be renewed
// silently while the tab is open. After a reload the user signs in again once the token expires.
let credentials = null;
let refreshToken = null;   // SSO sessions: memory only
let session = load();
const listeners = new Set();

function load() {
  try {
    const s = JSON.parse(sessionStorage.getItem(TOKEN_KEY) || 'null');
    return s && s.expiresAt > Date.now() ? s : null;
  } catch { return null; }
}

function save(s) {
  session = s;
  try {
    if (s) sessionStorage.setItem(TOKEN_KEY, JSON.stringify(s)); else sessionStorage.removeItem(TOKEN_KEY);
  } catch { /* storage unavailable: session lives in memory only */ }
  listeners.forEach((fn) => fn(s));
}

function decodeJwt(token) {
  const part = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
  const json = decodeURIComponent(atob(part).split('').map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0')).join(''));
  return JSON.parse(json);
}

async function requestToken(clientId, clientSecret) {
  const res = await fetch('/oauth2/token', {
    method: 'POST',
    headers: {
      'Authorization': 'Basic ' + btoa(`${clientId}:${clientSecret}`),
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: 'grant_type=client_credentials',
  });
  if (!res.ok) {
    let problem = {};
    try { problem = await res.json(); } catch { /* not JSON */ }
    if (res.status === 401 || res.status === 400) {
      problem.detail = 'Sign-in failed. Check the key ID and secret, and that the hospital is active.';
    }
    throw new ApiError(res.status, problem);
  }
  const body = await res.json();
  const claims = decodeJwt(body.access_token);
  const roles = claims.roles || [];
  return {
    token: body.access_token,
    expiresAt: Date.now() + (body.expires_in || 300) * 1000,
    clientId,
    role: roles.includes('OPS') ? 'OPS' : roles.includes('MERCHANT') ? 'MERCHANT' : null,
    merchantId: claims.mid || null,
  };
}

function sessionFromOidc(tokens) {
  const claims = decodeJwt(tokens.access_token);
  const roles = claims.roles || [];
  const role = roles.includes('OPS') ? 'OPS' : roles.includes('MERCHANT') && claims.mid ? 'MERCHANT' : null;
  refreshToken = tokens.refresh_token || null;
  return {
    kind: 'oidc',
    token: tokens.access_token,
    idToken: tokens.id_token || null,
    expiresAt: Date.now() + (tokens.expires_in || 300) * 1000,
    clientId: claims.preferred_username || claims.email || claims.sub,
    role,
    merchantId: claims.mid || null,
  };
}

export const auth = {
  get session() { return session; },
  onChange(fn) { listeners.add(fn); return () => listeners.delete(fn); },
  /** Single sign-on (OIDC): leaves the page for the provider's login. */
  signInWithSso() { return beginLogin(); },
  /** Finishes an SSO login when the page is the provider's redirect. Returns true if a session started. */
  async completeSso() {
    const result = await completeLogin();
    if (!result) return false;
    const s = sessionFromOidc(result.tokens);
    if (!s.role) {
      throw new ApiError(403, { detail: 'Your account has no dashboard access (needs the MERCHANT role with a hospital, or OPS).' });
    }
    save(s);
    return true;
  },
  async signIn(clientId, clientSecret, expectedRole) {
    const s = await requestToken(clientId.trim(), clientSecret.trim());
    if (!s.role) throw new ApiError(403, { detail: 'This client has no dashboard access.' });
    if (expectedRole && s.role !== expectedRole) {
      throw new ApiError(403, { detail: expectedRole === 'OPS'
        ? 'These are hospital credentials. Use the Hospital tab.'
        : 'These are operations credentials. Use the Operations tab.' });
    }
    credentials = { clientId: clientId.trim(), clientSecret: clientSecret.trim() };
    save(s);
    return s;
  },
  signOut(reason) {
    const ending = session;
    credentials = null;
    refreshToken = null;
    save(null);
    if (reason) sessionStorage.setItem('hpg.signout', reason);
    // A deliberate sign-out of an SSO session also ends it at the provider (otherwise the next click signs in again).
    if (ending?.kind === 'oidc' && !reason) endSession(ending.idToken);
  },
};

async function ensureToken() {
  if (session && session.expiresAt - Date.now() > 30_000) return session.token;
  if (credentials) {
    save(await requestToken(credentials.clientId, credentials.clientSecret));
    return session.token;
  }
  if (session?.kind === 'oidc') {
    if (refreshToken) {
      try {
        save(sessionFromOidc(await oidcRefresh(refreshToken)));
        return session.token;
      } catch { /* refresh token expired or revoked: fall through */ }
    }
    await beginLogin({ silent: true });   // the provider's SSO session renews it without a prompt
    throw new ApiError(401, { detail: 'Renewing your session…' });
  }
  auth.signOut('Your session expired. Please sign in again.');
  throw new ApiError(401, { detail: 'Session expired' });
}

/**
 * Calls the API through the gateway.
 * @param {string} path e.g. /api/v1/payments
 * @param {{method?: string, body?: any, query?: object, idempotencyKey?: string}} [opts]
 * @returns {Promise<{data: any, headers: Headers}>}
 */
export async function api(path, opts = {}) {
  const token = await ensureToken();
  const url = new URL(path, location.origin);
  for (const [k, v] of Object.entries(opts.query || {})) {
    if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, v);
  }
  const headers = { 'Authorization': `Bearer ${token}`, 'Accept': 'application/json' };
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  if (opts.idempotencyKey) headers['Idempotency-Key'] = opts.idempotencyKey;
  const res = await fetch(url, {
    method: opts.method || 'GET',
    headers,
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  });
  const text = await res.text();
  let data = null;
  if (text) {
    try { data = JSON.parse(text); } catch { data = { detail: text.slice(0, 200) }; }
  }
  if (res.status === 401) {
    auth.signOut('Your session is no longer valid. Please sign in again.');
  }
  if (!res.ok) throw new ApiError(res.status, data);
  return { data, headers: res.headers };
}

export const get = (path, query) => api(path, { query }).then((r) => r.data);

/** Multipart upload (the browser sets the multipart Content-Type with its boundary). */
export async function upload(path, formData, query) {
  const token = await ensureToken();
  const url = new URL(path, location.origin);
  for (const [k, v] of Object.entries(query || {})) url.searchParams.set(k, v);
  const res = await fetch(url, { method: 'POST', headers: { 'Authorization': `Bearer ${token}`, 'Accept': 'application/json' }, body: formData });
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { detail: text.slice(0, 200) }; }
  if (res.status === 413) throw new ApiError(413, { title: 'File too large', detail: 'Settlement files can be at most 20 MB.' });
  if (!res.ok) throw new ApiError(res.status, data);
  return data;
}

/** Downloads a non-JSON resource (e.g. a CSV report) and saves it under {@code filename}. */
export async function download(path, query, accept, filename) {
  const token = await ensureToken();
  const url = new URL(path, location.origin);
  for (const [k, v] of Object.entries(query || {})) url.searchParams.set(k, v);
  const res = await fetch(url, { headers: { 'Authorization': `Bearer ${token}`, 'Accept': accept } });
  if (!res.ok) {
    let problem = {};
    try { problem = await res.json(); } catch { /* not JSON */ }
    throw new ApiError(res.status, problem);
  }
  const blob = await res.blob();
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(link.href), 1000);
}

/** A fresh key per user action; reused if the same form is submitted twice. */
export const newIdempotencyKey = () => crypto.randomUUID();
