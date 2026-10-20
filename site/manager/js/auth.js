// Signing in with Google in the browser (Google Identity Services, token model): an access token for the
// shop's hidden app folder, held in memory only and good for about an hour. No refresh token, no server.
// The popup must open from a tap, so the client is made ahead of time and asked synchronously.

import { GOOGLE_CLIENT_ID, DRIVE_SCOPE } from './config.js';

const GIS = 'https://accounts.google.com/gsi/client';
let loading = null;
let client = null;
let waiting = null;
let token = null;
let expiresAt = 0;

export const configured = () => GOOGLE_CLIENT_ID.length > 0;

export class AuthError extends Error {
  constructor(code, message) {
    super(message || code);
    this.code = code;
  }
}

/** Loads Google's sign-in script and makes the token client (call early: before the tap). */
export function prepare() {
  if (!configured()) return Promise.resolve(false);
  if (!loading) {
    loading = new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = GIS;
      s.async = true;
      s.onload = () => resolve(true);
      s.onerror = () => { loading = null; reject(new AuthError('OFFLINE', 'Google sign-in could not load')); };
      document.head.appendChild(s);
    }).then(() => {
      client = window.google.accounts.oauth2.initTokenClient({
        client_id: GOOGLE_CLIENT_ID,
        scope: DRIVE_SCOPE,
        callback: (r) => {
          const w = waiting;
          waiting = null;
          if (!w) return;
          if (r.error) return w.reject(new AuthError(r.error === 'access_denied' ? 'NO_ACCESS' : 'SIGN_IN', r.error_description || r.error));
          if (!window.google.accounts.oauth2.hasGrantedAllScopes(r, DRIVE_SCOPE)) return w.reject(new AuthError('NO_ACCESS'));
          token = r.access_token;
          expiresAt = Date.now() + Number(r.expires_in ?? 3599) * 1000;
          w.resolve(token);
        },
        error_callback: (e) => {
          const w = waiting;
          waiting = null;
          w?.reject(new AuthError(e?.type === 'popup_closed' || e?.type === 'popup_failed_to_open' ? 'POPUP' : 'SIGN_IN', e?.message));
        },
      });
      return true;
    });
  }
  return loading;
}

/** A token still good for a few minutes, or null. */
export function current() {
  return token && Date.now() < expiresAt - 5 * 60_000 ? token : null;
}

/**
 * Asks Google for a token; call it from a tap. [hint]: the account used before (no account chooser);
 * [consent]: show Google's consent screen again (after access was refused).
 */
export function signIn(hint, consent = false) {
  if (!client) return prepare().then(() => signIn(hint, consent));
  return new Promise((resolve, reject) => {
    waiting?.reject(new AuthError('POPUP'));
    waiting = { resolve, reject };
    client.requestAccessToken({ prompt: consent ? 'consent' : '', login_hint: hint || undefined });
  });
}

export function forget() {
  if (token && window.google?.accounts?.oauth2) window.google.accounts.oauth2.revoke(token, () => {});
  token = null;
  expiresAt = 0;
}
