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
 *
 * The landing path is which public Fynora page the visit began on ("/help", a bank page), so each
 * page of the site can be measured by the accounts it brings in. It is NOT read from the address
 * bar. The build writes the page's own route into every prerendered public page as
 * <meta name="fynora-landing-path"> (scripts/prerenderTitle.mjs withLandingPath), and this reads that.
 * So only a page that really exists as a public page can ever be recorded: a typo, an address
 * someone made up, the 404 page, /auth and every /app page carry no marker. The marker must also
 * name the address the browser is on: a server that answers an unknown address with the homepage's
 * file (the local `wrangler dev` / `vite` fallback, or any future misconfiguration) would otherwise
 * make a mistyped address count as a visit to "/". Nothing from the
 * address bar's query or fragment is involved, so nothing personal can arrive this way. Unlike the
 * referrer and tags, a landing path is also kept for a visit with neither (typed in, bookmarked),
 * but only when the tab holds no capture at all: the FIRST public page, and never replaced by a
 * later page of the same tab. A new arrival that does carry a referrer or a tag starts over,
 * landing path included.
 */

export const SIGNUP_ATTRIBUTION_KEY = 'finora:signup-attribution';

/** Matches SignupAttributionService.UTM_MAX_LENGTH on the backend, which caps again. */
export const MAX_VALUE_LENGTH = 100;

/** The <meta name> the build puts on each prerendered public page; its content is the page's route. */
export const LANDING_PATH_META = 'fynora-landing-path';

/** Matches SignupAttributionService.LANDING_PATH_MAX_LENGTH and the V300 column, which check again. */
export const MAX_LANDING_PATH_LENGTH = 200;

export interface SignupAttribution {
  referrerHost?: string;
  utmSource?: string;
  utmMedium?: string;
  utmCampaign?: string;
  utmContent?: string;
  landingPath?: string;
}

const TAG_FIELDS = ['referrerHost', 'utmSource', 'utmMedium', 'utmCampaign', 'utmContent'] as const;

/** "/" or "/seg" or "/seg/seg": lower-case segments that do not start with a dot. The same shape
 *  SignupAttributionService.normalizeLandingPath and the V300 CHECK enforce. */
const LANDING_PATH = /^\/([a-z0-9_~-][a-z0-9._~-]*(\/[a-z0-9_~-][a-z0-9._~-]*)*)?$/;

/** The path if it is a plain public-page path of the shape the backend stores, else undefined. */
export function validLandingPath(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  if (value.length > MAX_LANDING_PATH_LENGTH || !LANDING_PATH.test(value)) return undefined;
  return value;
}

/**
 * The route the build wrote into this page, or undefined for a page that is not a prerendered public
 * page, or whose marker does not name the address the browser is on (`pathname`, a trailing slash
 * ignored): the document is then some other page's file served for this address.
 */
export function landingPathOfDocument(doc: Pick<Document, 'querySelector'>, pathname: string): string | undefined {
  try {
    const marker = validLandingPath(doc.querySelector(`meta[name="${LANDING_PATH_META}"]`)?.getAttribute('content'));
    if (!marker) return undefined;
    const here = pathname.length > 1 && pathname.endsWith('/') ? pathname.slice(0, -1) : pathname;
    return marker === here ? marker : undefined;
  } catch {
    return undefined;
  }
}

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
type LandingWindow = StorageWindow & {
  location: Pick<Location, 'host' | 'search' | 'pathname'>;
  document: Pick<Document, 'referrer' | 'querySelector'>;
};

function storage(win: StorageWindow): Storage | null {
  try {
    return win.sessionStorage ?? null;
  } catch {
    // Blocked storage (some private modes, some embedded contexts) throws on access.
    return null;
  }
}

/**
 * Called from main.tsx before the first render, on every full page load.
 *
 * - An arrival that carries a referrer or a tag is a new visit: it replaces what the tab held, with
 *   this page's landing path if it is a public page.
 * - An arrival that carries neither leaves the tab's capture alone, except that a tab holding
 *   nothing at all takes this page's landing path, if it is a public page: the first public page.
 *
 * Fails silently when storage is unavailable: attribution is a measurement, never something that may
 * break the page.
 */
export function captureSignupAttribution(win: LandingWindow): void {
  const store = storage(win);
  if (!store) return;
  const landingPath = landingPathOfDocument(win.document, win.location.pathname);
  const arrival = attributionFromLanding(win.document.referrer, win.location.search, win.location.host);
  let captured: SignupAttribution;
  if (Object.keys(arrival).length > 0) {
    captured = landingPath ? { ...arrival, landingPath } : arrival;
  } else if (landingPath && readSignupAttribution(win) === undefined) {
    captured = { landingPath };
  } else {
    return;
  }
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
  for (const key of TAG_FIELDS) {
    const value = record[key];
    if (typeof value === 'string' && value) out[key] = value.slice(0, MAX_VALUE_LENGTH);
  }
  // Re-checked on the way out, never cut: a shortened path would name a different page.
  const landingPath = validLandingPath(record.landingPath);
  if (landingPath) out.landingPath = landingPath;
  return Object.keys(out).length > 0 ? out : undefined;
}
