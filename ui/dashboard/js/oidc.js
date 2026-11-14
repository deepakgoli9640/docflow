// Single sign-on for people (hospital staff, operations) with OpenID Connect: Authorization Code + PKCE.
// The browser holds no client secret. Keycloak (or any OIDC provider) authenticates the user, enforces
// password policy, lockout and optional 2FA, and issues an access token with the claims the services read:
// roles ["MERCHANT"|"OPS"], mid (the hospital) and aud "hpg-api".
//
// Tokens: the access token lives in sessionStorage (like the client-credentials session); the refresh token
// only in memory. After a reload the provider's SSO session signs the user in again without a prompt.

const FLOW_KEY = 'hpg.oidc.flow';
let config = null;       // { authority, clientId } from /config.json, or null when SSO is off
let discovery = null;    // the provider's .well-known/openid-configuration

function b64url(bytes) {
  return btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function randomString(n = 32) {
  return b64url(crypto.getRandomValues(new Uint8Array(n)));
}

async function challenge(verifier) {
  return b64url(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
}

/** Loads the dashboard config and the provider's metadata. Resolves to false when SSO is unavailable. */
export async function oidcAvailable() {
  if (discovery) return true;
  try {
    const res = await fetch('/config.json', { cache: 'no-store' });
    if (!res.ok) return false;
    const c = await res.json();
    if (!c.oidc || !c.oidc.authority) return false;
    const d = await fetch(`${c.oidc.authority}/.well-known/openid-configuration`, { cache: 'no-store' });
    if (!d.ok) return false;
    config = c.oidc;
    discovery = await d.json();
    return true;
  } catch {
    return false;
  }
}

function redirectUri() {
  return `${location.origin}/`;
}

/** Sends the browser to the provider's login page. */
export async function beginLogin({ silent = false } = {}) {
  if (!(await oidcAvailable())) throw new Error('Single sign-on is not available.');
  const verifier = randomString(48);
  const state = randomString(16);
  sessionStorage.setItem(FLOW_KEY, JSON.stringify({ verifier, state, returnTo: location.hash || '#/overview' }));
  const params = new URLSearchParams({
    response_type: 'code',
    client_id: config.clientId,
    redirect_uri: redirectUri(),
    scope: 'openid profile',
    state,
    code_challenge: await challenge(verifier),
    code_challenge_method: 'S256',
  });
  if (silent) params.set('prompt', 'none');
  location.assign(`${discovery.authorization_endpoint}?${params}`);
}

async function tokenRequest(body) {
  const res = await fetch(discovery.token_endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: config.clientId, ...body }),
  });
  if (!res.ok) throw new Error(`Token request failed (${res.status})`);
  return res.json();
}

/**
 * Completes a login if the URL carries an authorization response (?code=…&state=…).
 * @returns {Promise<null | {tokens: object, returnTo: string}>} null when this is not a login callback
 */
export async function completeLogin() {
  const query = new URLSearchParams(location.search);
  if (!query.has('state') || !(query.has('code') || query.has('error'))) return null;
  const flow = JSON.parse(sessionStorage.getItem(FLOW_KEY) || 'null');
  sessionStorage.removeItem(FLOW_KEY);
  history.replaceState(null, '', location.pathname + (flow?.returnTo || ''));   // drop code and state from the URL
  if (!flow || flow.state !== query.get('state')) throw new Error('Sign-in was interrupted. Please try again.');
  if (query.has('error')) {
    if (query.get('error') === 'login_required') return null;   // silent renewal found no SSO session
    throw new Error(query.get('error_description') || 'Sign-in was cancelled.');
  }
  if (!(await oidcAvailable())) throw new Error('Single sign-on is not available.');
  const tokens = await tokenRequest({
    grant_type: 'authorization_code',
    code: query.get('code'),
    redirect_uri: redirectUri(),
    code_verifier: flow.verifier,
  });
  return { tokens, returnTo: flow.returnTo };
}

/** Exchanges a refresh token for new tokens. */
export async function refresh(refreshToken) {
  if (!(await oidcAvailable())) throw new Error('Single sign-on is not available.');
  return tokenRequest({ grant_type: 'refresh_token', refresh_token: refreshToken });
}

/** Ends the provider's session too, then comes back to the sign-in page. */
export async function endSession(idToken) {
  if (!(await oidcAvailable()) || !discovery.end_session_endpoint) return;
  const params = new URLSearchParams({ client_id: config.clientId, post_logout_redirect_uri: redirectUri() });
  if (idToken) params.set('id_token_hint', idToken);
  location.assign(`${discovery.end_session_endpoint}?${params}`);
}
