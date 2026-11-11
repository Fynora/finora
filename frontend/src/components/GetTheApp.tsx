import { useLocation } from 'react-router-dom';
import { Smartphone } from 'lucide-react';
import googlePlayBadge from '../assets/landing/google-play-badge.png';
import { type PlayPlacement, playQrPath, playStoreLink } from '../lib/playStoreLink';

export const GET_THE_APP_TITLE = 'Get Fynora on Android';
export const GET_THE_APP_TEXT = 'The same Fynora on your phone: import a statement and see where your money went.';
export const PLAY_BADGE_ALT = 'Get it on Google Play';
export const PLAY_QR_ALT = 'QR code that opens the Fynora app on Google Play';
export const PLAY_LEGAL = 'Google Play and the Google Play logo are trademarks of Google LLC.';

/**
 * Google's own "Get it on Google Play" badge, linking to the listing with this page's install
 * referrer (lib/playStoreLink.ts).
 *
 * The artwork is the same file the landing footer uses (assets/landing/google-play-badge.png,
 * Google's en_badge_web_generic.png, unmodified). Google's badge rules: no recolouring, cropping or
 * redrawing, scaled only in proportion, and clear space around it of a quarter of its height. This
 * PNG carries that clear space as transparent padding (41 of its 646 px on each side), so here it
 * is kept, not trimmed with a negative margin as the footer does: at 155x60 the visible badge is
 * about 40px tall, Apple's and Google's comfortable on-screen size, with ~10px clear on every side.
 *
 * Sized and lazy: the width and height reserve its box before the file arrives (no layout shift),
 * and `loading="lazy"` keeps it from competing with the page's text for the first paint when it is
 * below the fold.
 */
export function PlayBadge({ placement, className = '' }: { placement: PlayPlacement; className?: string }) {
  const { pathname } = useLocation();
  return (
    <a
      href={playStoreLink(pathname, placement)}
      target="_blank"
      rel="noopener noreferrer"
      className={`inline-block shrink-0 ${className}`}
      data-play-link={placement}
    >
      <img src={googlePlayBadge} alt={PLAY_BADGE_ALT} width={155} height={60} loading="lazy" decoding="async" className="block" />
    </a>
  );
}

/**
 * The QR code for this page's Play link: a static SVG the build writes for every prerendered page
 * (scripts/prerender.mjs), encoding `playStoreLink(page, 'qr')`. Only from tablet width up: a phone
 * cannot scan its own screen. The white tile is the code's own quiet zone, which a scanner needs.
 */
export function PlayQr({ size = 128, className = 'hidden md:block' }: { size?: number; className?: string }) {
  const { pathname } = useLocation();
  return (
    <img
      src={playQrPath(pathname)}
      alt={PLAY_QR_ALT}
      width={size}
      height={size}
      loading="lazy"
      decoding="async"
      className={`${className} rounded-lg`}
    />
  );
}

/**
 * "Get Fynora on Android", for the public pages built for search (bank pages, the statement code
 * glossary, Help answers, the converter). Never under /app or on the sign-in pages: those do not
 * use it. Three shapes:
 *
 *  - `compact`: one line and the badge, for right after a page's answer.
 *  - `full`: a card with the badge, the QR code from tablet width up, and Google's trademark line.
 *  - `sidebar`: the QR code over the badge, for the bank pages' desktop sidebar.
 *
 * Every Play link carries the page's install referrer, with `placement` as utm_term.
 *
 * A labelled group, not an <aside>: a bank page shows two of these at any width, and two
 * complementary landmarks with the same name inside <main> fail axe's landmark-unique rule.
 */
export function GetTheApp({
  variant,
  placement,
  className = '',
  qrClassName,
}: {
  variant: 'compact' | 'full' | 'sidebar';
  placement: PlayPlacement;
  className?: string;
  /** Where the `full` card's QR code shows. Default: tablet width up. */
  qrClassName?: string;
}) {
  if (variant === 'sidebar') {
    return (
      <div role="group" aria-label={GET_THE_APP_TITLE} className={`bg-card border border-border rounded-xl p-4 ${className}`}>
        <p className="text-sm font-semibold text-ink mb-1">{GET_THE_APP_TITLE}</p>
        <p className="text-xs text-muted mb-3">Scan with your phone&apos;s camera.</p>
        <PlayQr size={136} />
        <PlayBadge placement={placement} className="mt-1 -ml-2" />
        <p className="text-[11px] text-muted leading-snug mt-2">{PLAY_LEGAL}</p>
      </div>
    );
  }

  if (variant === 'compact') {
    return (
      <div
        role="group"
        aria-label={GET_THE_APP_TITLE}
        className={`bg-card border border-border rounded-xl px-4 py-2 flex flex-wrap items-center justify-between gap-x-4 ${className}`}
      >
        <p className="flex items-center gap-2 text-sm text-ink py-2">
          <Smartphone size={16} className="text-primary shrink-0" aria-hidden="true" />
          <span><span className="font-semibold">Fynora is on Android.</span> Import statements from your phone.</span>
        </p>
        <PlayBadge placement={placement} className="-mx-2" />
      </div>
    );
  }

  return (
    <div role="group" aria-label={GET_THE_APP_TITLE} className={`bg-card border border-border rounded-xl p-6 ${className}`}>
      <div className="flex flex-wrap items-center gap-6">
        <PlayQr className={qrClassName} />
        <div className="flex-1 min-w-[220px]">
          <p className="text-lg font-semibold text-ink mb-1">{GET_THE_APP_TITLE}</p>
          <p className="text-sm text-muted mb-2">{GET_THE_APP_TEXT}</p>
          <PlayBadge placement={placement} className="-ml-2" />
          <p className="text-[11px] text-muted leading-snug mt-1">{PLAY_LEGAL}</p>
        </div>
      </div>
    </div>
  );
}
