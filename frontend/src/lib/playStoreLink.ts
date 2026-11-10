import { PLAY_STORE_URL } from './openInApp';

/**
 * Google Play links that say which page of this site sent the visitor, so installs can be counted
 * by page in Play Console.
 *
 * Google Play keeps the `referrer` parameter of a store link and hands it to the app as its
 * install referrer. Its value is a query string of utm_ tags, URL-encoded as a whole, so `=`
 * becomes %3D and `&` becomes %26 (Google's Play URL builder and Play Console help use this form).
 * Play Console's store listing reports break traffic down by two of those tags only, utm_source
 * and utm_campaign ("The value of the utm_campaign URL parameter in deep links to your store
 * listing", support.google.com/googleplay/android-developer/answer/9859173, read 2026-11-10), and
 * only under the "Ads and referrals" traffic source. So the campaign carries the page, and the
 * other tags carry the same thing split up for any tool that reads the full referrer:
 *
 *   utm_source   fynora_web, every link from this site
 *   utm_medium   the page type: bank_page, help_article, statement_code, converter, ...
 *   utm_content  the page's slug: hdfc-bank-statement, upi, ...
 *   utm_campaign <page type>-<slug>, the one Play Console shows per page
 *   utm_term     where on the page: badge_top, badge_bottom, sidebar, qr, banner, ...
 *
 * Everything is derived from the page's address, so the prerendered HTML and the browser render
 * produce the same link, and a page added later is attributed without an edit here.
 *
 * The plain listing (PLAY_STORE_URL, no referrer) stays where the link names the app rather than
 * sending a visitor: structured data's sameAs and llms.txt.
 */

/** utm_source on every link from this site. */
export const PLAY_REFERRER_SOURCE = 'fynora_web';

/** Where on a page a Play link sits. Carried as utm_term. */
export type PlayPlacement =
  | 'badge_top'
  | 'badge_bottom'
  | 'sidebar'
  | 'qr'
  | 'result'
  | 'banner'
  | 'footer'
  | 'text';

export interface PageAttribution {
  /** utm_medium. Lower case letters and underscores. */
  pageType: string;
  /** utm_content. Lower case letters, digits and hyphens. */
  slug: string;
}

const SECTIONS: { prefix: string; hub: string; page: string }[] = [
  { prefix: '/banks', hub: 'banks_hub', page: 'bank_page' },
  { prefix: '/help', hub: 'help_hub', page: 'help_article' },
  { prefix: '/bank-statement-codes', hub: 'statement_codes_hub', page: 'statement_code' },
];

/** Lower case, anything outside [a-z0-9-] becomes a hyphen, at most 60 characters. */
function cleanSlug(raw: string): string {
  const slug = raw.toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60);
  return slug || 'unknown';
}

/** The page type and slug for an address on this site. A trailing slash is ignored. */
export function pageAttribution(pathname: string): PageAttribution {
  const path = pathname.replace(/\/+$/, '') || '/';
  if (path === '/') return { pageType: 'home', slug: 'home' };
  if (path === '/bank-statement-to-excel') return { pageType: 'converter', slug: 'bank-statement-to-excel' };
  for (const section of SECTIONS) {
    if (path === section.prefix) return { pageType: section.hub, slug: cleanSlug(section.prefix.slice(1)) };
    if (path.startsWith(section.prefix + '/')) {
      return { pageType: section.page, slug: cleanSlug(path.slice(section.prefix.length + 1)) };
    }
  }
  return { pageType: 'page', slug: cleanSlug(path.slice(1)) };
}

function referrerFor({ pageType, slug }: PageAttribution, placement: PlayPlacement): string {
  return [
    `utm_source=${PLAY_REFERRER_SOURCE}`,
    `utm_medium=${pageType}`,
    `utm_campaign=${pageType}-${slug}`,
    `utm_content=${slug}`,
    `utm_term=${placement}`,
  ].join('&');
}

function linkFor(attribution: PageAttribution, placement: PlayPlacement): string {
  return `${PLAY_STORE_URL}&referrer=${encodeURIComponent(referrerFor(attribution, placement))}`;
}

/** The install referrer itself, before encoding: `utm_source=fynora_web&utm_medium=...`. */
export function playReferrer(pathname: string, placement: PlayPlacement): string {
  return referrerFor(pageAttribution(pathname), placement);
}

/** The Google Play listing with this page's install referrer. */
export function playStoreLink(pathname: string, placement: PlayPlacement): string {
  return linkFor(pageAttribution(pathname), placement);
}

/**
 * The QR code image for a page, a static SVG the build writes (scripts/prerender.mjs) and the dev
 * server answers (vite.config.ts). It encodes `playStoreLink(page, 'qr')`; the file name carries
 * the page type and slug so the code can be rebuilt from the name alone (playQrLinkForFile).
 */
export function playQrPath(pathname: string): string {
  const { pageType, slug } = pageAttribution(pathname);
  return `/qr/play-${pageType}-${slug}.svg`;
}

const QR_FILE = /^\/qr\/play-([a-z_]+)-([a-z0-9-]+)\.svg$/;

/**
 * The link a QR file encodes, read back from its path, or null when the path is not one
 * playQrPath makes. The page type has no hyphen, so the first hyphen after "play-" ends it.
 */
export function playQrLinkForFile(qrPath: string): string | null {
  const match = QR_FILE.exec(qrPath);
  if (!match) return null;
  const [, pageType, slug] = match;
  return linkFor({ pageType, slug }, 'qr');
}
