import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';

interface MerchantLogoProps {
  /** The name shown: used for the initials badge and the tooltip, never sent anywhere. */
  merchant: string;
  /** The web address to ask Logo.dev for: the server's `logoDomain`, chosen from its own brand list
   *  (backend MerchantBrands). Null or absent means no request at all, only initials. */
  domain?: string | null;
  size?: number;
  /** Custom content to show when Logo.dev has no logo for this merchant (or is unconfigured).
   *  Rendered as-is, with no wrapper of its own -- the caller owns its container (see Dashboard's
   *  usage, which reuses its existing colored circle). Omit to get this component's own
   *  self-contained colored-initials badge instead (see Ledger's usage). */
  fallback?: ReactNode;
  /** Controls the image/initials-badge shape. Defaults to `rounded-xl`, matching BankLogo, so a
   *  merchant logo and a bank logo look like the same kind of object elsewhere in the app. */
  className?: string;
}

const LOGODEV_TOKEN = import.meta.env.VITE_LOGODEV_TOKEN;
// Same budget as BankLogo -- see that component's own comment for why.
const LOGODEV_TIMEOUT_MS = 1500;

/** A bare lowercase domain such as `swiggy.com`. Anything else -- a name, a narration, a path --
 *  is refused, so a caller passing the wrong field still cannot send text to Logo.dev. */
const BARE_DOMAIN = /^[a-z0-9-]+(\.[a-z0-9-]+)+$/;

export function logoDevUrl(domain: string | null | undefined, sizePx: number, token: string | undefined): string | null {
  const d = domain?.trim();
  if (!token || !d || !BARE_DOMAIN.test(d)) return null;
  // https://www.logo.dev/docs/logo-images/get -- a bare domain is the default identifier type,
  // as in BankLogo. Same format=png/fallback=404 reasoning as BankLogo.logoDevUrl.
  return `https://img.logo.dev/${d}?token=${token}&size=${sizePx}&format=png&fallback=404`;
}

type Stage = 'logodev' | 'fallback';

function initialsOf(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return '?';
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase();
  return (words[0][0] + words[1][0]).toUpperCase();
}

// Deterministic name -> color, so the same merchant always gets the same badge color across rows
// and pages rather than a new random one on every render.
function colorFor(name: string): string {
  let hash = 0;
  for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) | 0;
  // 30% lightness so the white initials clear WCAG AA on every hue: at the 40% this used to be,
  // yellow-green hues put white at 2.84:1; the worst hue at 30% is 4.75:1.
  return `hsl(${Math.abs(hash) % 360}, 55%, 30%)`;
}

/**
 * Merchant logo: Logo.dev looked up by the server's `logoDomain` only -> a colored-initials badge,
 * or a caller-supplied fallback. It used to look the merchant's own name up, which sent people's
 * names and bank narration to Logo.dev (measured on the statement corpus, 2026-10-24); now only an
 * address from the server's brand list ever leaves the app.
 *
 * <b>Deliberately no circuit breaker, unlike BankLogo.</b> BankLogo's breaker exists because its
 * catalog is small and fixed (the bank registry) -- a real token/config rejection there shows up
 * identically across every bank on the page, so tripping once and skipping the rest is a correct
 * inference. A transaction's merchant name is neither small nor fixed: most rows in a real ledger
 * are cash withdrawals, UPI/IMPS references, or small vendors Logo.dev's catalog was never going
 * to have -- a miss there is the ordinary, expected outcome for THAT merchant, not evidence the
 * whole integration is broken. Tripping a shared breaker on the first unrecognized name would
 * silently stop looking up every subsequent row too, including ones for merchants (Swiggy,
 * Amazon) that would have resolved fine. Each row's `<img>` requests independently and falls back
 * on its own; a page of transactions costs at most one concurrent request per visible row, not a
 * cascading failure.
 */
export function MerchantLogo({ merchant, domain = null, size = 32, fallback, className = 'rounded-xl' }: MerchantLogoProps) {
  const sizePx = Math.max(64, Math.round(size * 2));
  const src = logoDevUrl(domain, sizePx, LOGODEV_TOKEN);

  const [stage, setStage] = useState<Stage>(() => (src ? 'logodev' : 'fallback'));
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Reset whenever the merchant itself changes -- e.g. scrolling a transaction list, each row a
  // different merchant -- otherwise a row that previously fell back for one merchant would
  // incorrectly start there for the next.
  useEffect(() => {
    setStage(src ? 'logodev' : 'fallback');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [merchant, domain]);

  useEffect(() => {
    if (stage !== 'logodev') return undefined;
    timeoutRef.current = setTimeout(() => setStage('fallback'), LOGODEV_TIMEOUT_MS);
    return () => { if (timeoutRef.current) clearTimeout(timeoutRef.current); };
  }, [stage, merchant]);

  function clearLogoTimeout() {
    if (timeoutRef.current) { clearTimeout(timeoutRef.current); timeoutRef.current = null; }
  }

  if (stage === 'logodev' && src) {
    return (
      <img
        src={src}
        alt={merchant}
        title={merchant}
        className={`object-contain flex-shrink-0 ${className}`}
        style={{ width: size, height: size }}
        onLoad={clearLogoTimeout}
        onError={() => { clearLogoTimeout(); setStage('fallback'); }}
      />
    );
  }

  if (fallback !== undefined) return <>{fallback}</>;

  return (
    <div
      className={`flex items-center justify-center flex-shrink-0 font-bold text-white ${className}`}
      style={{ width: size, height: size, background: colorFor(merchant || '?'), fontSize: Math.max(9, size * 0.34) }}
      title={merchant}
    >
      {initialsOf(merchant || '')}
    </div>
  );
}
