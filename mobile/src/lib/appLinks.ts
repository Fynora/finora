// The host and path lists live in appLinks.config.js (plain JS, because app.config.ts has to import
// them and Expo's config loader can't resolve a .ts file); this module adds the URL parsing.
import { APP_LINK_EXACT_PATHS, APP_LINK_HOSTS, APP_LINK_PATH_PREFIXES } from '../../appLinks.config';

export { APP_LINK_EXACT_PATHS, APP_LINK_HOSTS, APP_LINK_PATH_PREFIXES };

export interface ParsedAppLink {
  /** Leading-slash path, trailing slash removed -- "/verify-email", "/app/imports/abc". */
  path: string;
  params: Record<string, string>;
}

const escapeForRegExp = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

const HOSTS_PATTERN = Object.values(APP_LINK_HOSTS).map(escapeForRegExp).join('|');

// https://<host>[/path][?query][#fragment]
const HTTPS_LINK = new RegExp(`^https://(?:${HOSTS_PATTERN})((?:/[^?#]*)?)(?:\\?([^#]*))?(?:#.*)?$`, 'i');
// finora://<first-segment>[/rest][?query] -- the first segment is where a custom scheme puts what
// an https URL calls the first path component ("finora://verify-email" == "/verify-email").
const SCHEME_LINK = /^finora(?:-dev)?:\/\/([^?#/]*)([^?#]*)(?:\?([^#]*))?(?:#.*)?$/i;

/**
 * Normalises every URL shape this app is handed -- the custom scheme and the https app link --
 * into one { path, params }. Returns null for anything that isn't ours or is malformed, so callers
 * can pass any URL the OS delivers without a prior "is this ours?" check.
 *
 * Deliberately not URL/URLSearchParams: whether Hermes provides them without a polyfill this repo
 * doesn't have is unverified, and a regex plus manual split needs nothing beyond the language.
 */
export function parseAppLink(url: string): ParsedAppLink | null {
  let path: string;
  let query: string | undefined;

  const https = HTTPS_LINK.exec(url);
  if (https) {
    path = https[1] || '/';
    query = https[2];
  } else {
    const scheme = SCHEME_LINK.exec(url);
    if (!scheme || !scheme[1]) return null;
    path = `/${scheme[1]}${scheme[2]}`;
    query = scheme[3];
  }
  if (path.length > 1 && path.endsWith('/')) path = path.slice(0, -1);

  const params: Record<string, string> = {};
  if (query) {
    try {
      for (const pair of query.split('&')) {
        const eq = pair.indexOf('=');
        if (eq <= 0) continue;
        params[decodeURIComponent(pair.slice(0, eq))] = decodeURIComponent(pair.slice(eq + 1));
      }
    } catch {
      // A stray "%" that isn't an escape: treat the whole link as malformed rather than half-read.
      return null;
    }
  }
  return { path, params };
}

/** True when `path` is `prefix` itself or anything beneath it (`/app/imports` -> `/app/imports/x`). */
export function pathIsUnder(path: string, prefix: string): boolean {
  return path === prefix || path.startsWith(`${prefix}/`);
}

/** True when the OS would hand `path` to the app: an exact claimed path, or under a claimed prefix. */
export function isClaimedPath(path: string): boolean {
  return APP_LINK_EXACT_PATHS.includes(path) || APP_LINK_PATH_PREFIXES.some((p) => pathIsUnder(path, p));
}

/**
 * Linking.getInitialURL() keeps returning the URL the process was launched with for the whole JS
 * runtime, and RootErrorBoundary's "Try again" remounts RootNavigator -- and with it every deep-link
 * hook -- from scratch. Without a guard each hook re-handles the launch link after a crash recovery
 * (for the emailed verify/confirm links that means acting on a single-use token already spent).
 *
 * Call this once at MODULE scope in each hook file, and pass the launch URL through the returned
 * function: true the first time this runtime sees that URL, false after. One guard per hook, not one
 * shared: every hook sees the same launch URL, so a shared set would let whichever hook mounts first
 * claim it and starve the rest. Only the launch URL goes through it -- a live 'url' event for the
 * same link is a new delivery (the user tapped it again), not a replay.
 */
export function createLaunchUrlGuard(): (url: string) => boolean {
  const seen = new Set<string>();
  return (url) => {
    if (seen.has(url)) return false;
    seen.add(url);
    return true;
  };
}

