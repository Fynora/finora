import * as WebBrowser from 'expo-web-browser';
import { gmailApi } from '../api/endpoints';

/**
 * Must match GoogleOAuthProperties.postConnectRedirectMobile on the backend, and app.config.ts's
 * own `scheme` (finora in production, finora-dev for a development-variant build). See
 * ReturnPlatform's own doc comment (backend/.../integrations/google/ReturnPlatform.java) for why
 * the backend picks this target itself, from a closed enum stored server-side, rather than
 * trusting anything this app sends it -- this constant only has to match what the BACKEND is
 * separately configured to redirect to; it is never sent as part of any request this app makes.
 */
const REDIRECT_URL = 'finora://gmail-callback';

export type GmailConnectOutcome = 'connected' | 'declined' | 'failed' | 'cancelled';

/**
 * Parses the `gmail` query param off GoogleOAuthController#callback's redirect URL. Deliberately
 * not URL/URLSearchParams -- see useEmailChangeDeepLink.ts's identical reasoning: unverified
 * whether those are globally available in this Hermes runtime without a polyfill this repo
 * doesn't have. A plain regex needs nothing beyond what's already guaranteed.
 */
function parseGmailOutcome(url: string): string | null {
  const match = /[?&]gmail=([^&]+)/.exec(url);
  return match ? decodeURIComponent(match[1]) : null;
}

/**
 * Starts the Gmail OAuth flow: asks the backend for Google's consent URL (gmailApi.connect
 * already tags the request platform=MOBILE), opens it in an auth session, and waits for the OS
 * to hand control back once Google's flow redirects to REDIRECT_URL.
 * GoogleOAuthController#callback resolves that exact target from the state gmailApi.connect()
 * just created server-side (see ReturnPlatform) -- this function only hands REDIRECT_URL to
 * openAuthSessionAsync, which needs it to recognize when the flow is done; it is never sent to
 * the backend as part of any request.
 *
 * 'cancelled' covers the auth session ending before Google's flow ever redirected back (the user
 * closed the browser, or the OS-level prompt was dismissed before reaching Google) -- there is no
 * `gmail=` query param to read in that case. That's distinct from 'declined': the user reaching
 * Google's own consent screen and pressing Cancel there DOES redirect back, with `gmail=declined`
 * (see GoogleOAuthController#callback's own doc comment on why the outcome always travels as a
 * query parameter, never anything that could carry a token).
 */
export async function connectGmail(): Promise<GmailConnectOutcome> {
  const { authorizationUrl } = await gmailApi.connect();
  const result = await WebBrowser.openAuthSessionAsync(authorizationUrl, REDIRECT_URL);
  if (result.type !== 'success') return 'cancelled';
  const outcome = parseGmailOutcome(result.url);
  return outcome === 'connected' || outcome === 'declined' ? outcome : 'failed';
}
