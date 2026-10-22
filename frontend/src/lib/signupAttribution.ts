/**
 * Where this visit came from, remembered for the sign-up that may end it.
 *
 * Nothing in the app records how a person found Fynora, so no channel (search, a reel, a
 * directory listing, a link in a message) can be measured by the accounts it brings in. This
 * captures, on each page load, the host of the page that linked here and the campaign tags on the
 * link, and AuthContext sends them with the registration request. The backend
 * (SignupAttributionService) decides what of it is stored and caps every value again.
 *
 * sessionStorage, deliberately: it lives for the tab and dies with it. Not a cookie, not
 * localStorage -- the visit is what is being measured, so nothing here should outlive it.
 *
 * The latest arrival with something to say wins. A landing that carries a referrer or a tag
 * replaces what the tab held (a tab reused days later should not keep attributing to its first
 * visit); a landing with neither (typed in, a reload of an in-app page, a bookmark) leaves the
 * earlier capture in place, since it says nothing about where the person came from. Within one
 * visit a reload keeps the same referrer, so re-capturing changes nothing.
 *
 * Only the referrer's host is kept, never its path or query: a full referrer URL can carry
 * another site's session tokens. A referrer on this site's own host is dropped, since that is
 * the app linking to itself, not a channel. No analytics SDK, no identifiers.
 */

export const SIGNUP_ATTRIBUTION_KEY = 'finora:signup-attribution';

/** Matches SignupAttributionService.UTM_MAX_LENGTH on the backend, which caps again. */
export const MAX_VALUE_LENGTH = 100;

export interface SignupAttribution {
  referrerHost?: string;
  utmSource?: string;
  utmMedium?: string;
  utmCampaign?: string;
  utmContent?: string;
}

const FIELDS = ['referrerHost', 'utmSource', 'utmMedium', 'utmCampaign', 'utmContent'] as const;

const UTM_PARAMS: ReadonlyArray<[keyof SignupAttribution, string]> = [
  ['utmSource', 'utm_source'],
  ['utmMedium', 'utm_medium'],
  ['utmCampaign', 'utm_campaign'],
  ['utmContent', 'utm_content'],
];

/**
 * The referrer's host name, lower-cased, or undefined when there is no referrer, it cannot be
 * parsed, or it is this site itself (`ownHost` is `location.host`; the port is ignored on both
 * sides, so a local dev server linking to itself is dropped too).
 */
export function referrerHost(referrer: string, ownHost: string): string | undefined {
  if (!referrer) return undefined;
  let host: string;
  try {
    host = new URL(referrer).hostname.toLowerCase();
  } catch {
    return undefined;
  }
  if (!host) return undefined;
  const own = ownHost.toLowerCase().replace(/:\d+$/, '');
  if (host === own) return undefined;
  return host.slice(0, MAX_VALUE_LENGTH);
}

/** What a landing with this referrer and query string says about where it came from. */
export function attributionFromLanding(referrer: string, search: string, ownHost: string): SignupAttribution {
  const out: SignupAttribution = {};
  const host = referrerHost(referrer, ownHost);
  if (host) out.referrerHost = host;
  const params = new URLSearchParams(search);
  for (const [key, param] of UTM_PARAMS) {
    const value = params.get(param)?.trim();
    if (value) out[key] = value.slice(0, MAX_VALUE_LENGTH);
  }
  return out;
}

type StorageWindow = Pick<Window, 'sessionStorage'>;
type LandingWindow = StorageWindow & { location: Pick<Location, 'host' | 'search'>; document: Pick<Document, 'referrer'> };

function storage(win: StorageWindow): Storage | null {
  try {
    return win.sessionStorage ?? null;
  } catch {
    // Blocked storage (some private modes, some embedded contexts) throws on access.
    return null;
  }
}

/**
 * Called from main.tsx before the first render. Stores this landing when it carries a referrer
 * or a tag, leaves the tab's earlier capture alone when it carries neither, and fails silently
 * when storage is unavailable: attribution is a measurement, never something that may break the
 * page.
 */
export function captureSignupAttribution(win: LandingWindow): void {
  const store = storage(win);
  if (!store) return;
  const captured = attributionFromLanding(win.document.referrer, win.location.search, win.location.host);
  if (Object.keys(captured).length === 0) return;
  try {
    store.setItem(SIGNUP_ATTRIBUTION_KEY, JSON.stringify(captured));
  } catch {
    // Quota or a storage that throws on write: nothing to do.
  }
}

/** The tab's capture, or undefined when there is none or it holds nothing worth sending. */
export function readSignupAttribution(win: StorageWindow): SignupAttribution | undefined {
  const store = storage(win);
  if (!store) return undefined;
  let parsed: unknown;
  try {
    const raw = store.getItem(SIGNUP_ATTRIBUTION_KEY);
    if (!raw) return undefined;
    parsed = JSON.parse(raw);
  } catch {
    return undefined;
  }
  if (!parsed || typeof parsed !== 'object') return undefined;
  const out: SignupAttribution = {};
  const record = parsed as Record<string, unknown>;
  for (const key of FIELDS) {
    const value = record[key];
    if (typeof value === 'string' && value) out[key] = value.slice(0, MAX_VALUE_LENGTH);
  }
  return Object.keys(out).length > 0 ? out : undefined;
}
