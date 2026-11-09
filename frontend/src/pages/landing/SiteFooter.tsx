import { Link } from 'react-router-dom';
import { Logo } from './Nav';
import { footer } from './landing-config';
import { APP_STORE_URL, PLAY_STORE_URL } from '../../lib/openInApp';
import playStoreQr from '../../assets/landing/play-store-qr.svg';
import appStoreQr from '../../assets/landing/app-store-qr.svg';
import googlePlayBadge from '../../assets/landing/google-play-badge.png';
import appStoreBadge from '../../assets/landing/app-store-badge.svg';

// lucide-react dropped brand/logo icons (Instagram included) -- kept as an inline glyph rather
// than pulling in a whole icon-brand package for a single footer link.
function InstagramIcon({ size = 17 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="2" y="2" width="20" height="20" rx="5" ry="5" />
      <path d="M16 11.37A4 4 0 1 1 12.63 8 4 4 0 0 1 16 11.37z" />
      <line x1="17.5" y1="6.5" x2="17.51" y2="6.5" />
    </svg>
  );
}



const COLUMNS: { title: string; links: [string, string][] }[] = [
  { title: 'Product', links: [['How it works', '#how'], ['What it does', '#features'], ['Ask Fyn', '#ask-fyn'], ['Security', '#security'], ['Pricing', '#pricing'], ['FAQ', '#faq'], ['Bank statements', '/banks']] },
  { title: 'Company', links: [['About', '/about'], ['Careers', '/careers'], ['Contact', '/contact'], ['Help Center', '/help']] },
  { title: 'Legal', links: [['Privacy Policy', '/privacy'], ['Terms of Service', '/terms'], ['Cookie Policy', '/cookie-policy'], ['Trust & Security', '/trust'], ['Your Data', '/your-data'], ['Refund Policy', '/refund-policy'], ['Shipping Policy', '/shipping-policy']] },
];

/**
 * The three lines under the mark are the page's argument compressed to six words, and they are
 * the last thing read.
 *
 * Instagram is the only social link, because it is the only account that exists. A row of icons
 * where most lead nowhere reads as abandoned; one that goes somebody real reads as a company.
 * `rel="noopener noreferrer"` on the external link is not optional -- `target="_blank"` without
 * it hands the opened tab a reference back to this one.
 *
 * The two QR codes are static SVGs (assets/landing/play-store-qr.svg and app-store-qr.svg) that
 * encode PLAY_STORE_URL and APP_STORE_URL exactly. They were generated offline with the `qrcode`
 * package (error correction M, 4-module quiet zone) and checked by decoding the rendered image.
 * They do not follow the constants: if either URL in lib/openInApp.ts ever changes, regenerate the
 * matching SVG for the new URL and decode it again before shipping, or the code will send people
 * to a dead listing.
 *
 * The badges are the stores' own artwork, unmodified: google-play-badge.png is Google's
 * en_badge_web_generic.png from play.google.com/intl/en_us/badges/, and app-store-badge.svg is
 * Apple's download-on-the-app-store.svg (black) from developer.apple.com/assets/elements/badges/.
 * If either is ever replaced, take the new file from the same place, never a stock-photo copy.
 * Both apps are on their stores (Everywhere.tsx says so); a store that has no listing gets no
 * badge here.
 */
export function SiteFooter() {
  return (
    <footer style={{ background: 'var(--color-deep-surface)' }}>
      <div className="max-w-6xl mx-auto px-5 sm:px-6 py-14">
        <div className="grid md:grid-cols-[1.4fr_1fr_1fr_1fr] gap-10 mb-10">
          <div>
            <Logo invert />
            <p className="text-sm mt-3 max-w-xs leading-relaxed text-slate-400">
              {footer.mission}
            </p>
            <div className="mt-5 space-y-1">
              {footer.principles.map((line) => (
                <p key={line} className="text-[13px] font-medium text-slate-400">{line}</p>
              ))}
            </div>

            <a
              href={footer.instagram}
              target="_blank"
              rel="noopener noreferrer"
              className="m-tap inline-flex items-center gap-2 mt-5 text-sm text-slate-400 hover:text-white transition-colors"
              aria-label="Fynora on Instagram (opens in a new tab)"
            >
              <InstagramIcon size={17} />
              {footer.instagramHandle}
            </a>

            <p className="mt-7 mb-3 text-xs font-semibold uppercase tracking-wide text-slate-300">{footer.appLabel}</p>

            {/* Each store is one unit: its QR code with its own badge directly beneath, so the code
                and the badge it belongs to can never drift apart. A phone cannot scan its own
                screen, so the codes only appear from tablet width up; on a phone each unit is just
                its badge. Each white tile is its SVG's own quiet zone, which a scanner needs, so it
                is not cropped or tinted. Units wrap instead of shrinking where the column is
                narrow, so a code never drops below 104px.

                Both badges are the stores' own artwork, shown unmodified: Google's and Apple's
                rules forbid redrawing, recoloring, cropping or distorting them, so they are only
                scaled in proportion. The two have different shapes (Google's is wider for its
                height), so both are sized to the same visible WIDTH, 129px, which is also what
                lines them up as a pair. That makes Apple's 43px tall and Google's 38px, both over
                the stores' minimums (Apple 40px, Google 28px). Google's PNG carries 41/646 of its
                width as transparent clear space on every side; the negative margin (9.4px at this
                size) removes it from the layout so the visible badge, not its padding, sits flush
                with the code above and with Apple's. Apple's SVG has none. The gap under each code
                is 12px, over the quarter-of-the-badge-height clear space Apple asks for. */}
            <div className="flex flex-wrap items-start gap-x-3 gap-y-5">
              <div className="flex flex-col items-start gap-3">
                <img src={playStoreQr} alt={footer.playQrAlt} width={104} height={104} className="hidden md:block rounded-lg" />
                <a
                  href={PLAY_STORE_URL}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="m-tap inline-block"
                >
                  <img src={googlePlayBadge} alt={footer.playStoreCta} width={148} height={57} className="block h-auto max-w-none -m-[9.4px]" />
                </a>
              </div>
              <div className="flex flex-col items-start gap-3">
                <img src={appStoreQr} alt={footer.appStoreQrAlt} width={104} height={104} className="hidden md:block rounded-lg" />
                <a
                  href={APP_STORE_URL}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="m-tap inline-block"
                >
                  <img src={appStoreBadge} alt={footer.appStoreCta} width={129} height={43} className="block h-auto" />
                </a>
              </div>
            </div>
            <p className="text-[11px] mt-4 max-w-[260px] leading-snug text-slate-400">{footer.trademark}</p>
          </div>

          {COLUMNS.map((col) => (
            <div key={col.title}>
              <p className="text-xs font-semibold uppercase tracking-wide text-slate-300 mb-3">{col.title}</p>
              <ul className="space-y-1">
                {col.links.map(([label, href]) => (
                  <li key={label}>
                    {href.startsWith('#') ? (
                      <a href={href} className="m-tap text-sm text-slate-400 hover:text-white transition-colors">{label}</a>
                    ) : (
                      <Link to={href} className="m-tap text-sm text-slate-400 hover:text-white transition-colors">{label}</Link>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>

        <div className="pt-7 border-t border-white/10 flex flex-col sm:flex-row items-center justify-between gap-3">
          <p className="text-xs text-slate-400">© {new Date().getFullYear()} Fynora Technovation LLP. All rights reserved.</p>
          <p className="text-xs text-slate-400">{footer.tagline}</p>
        </div>
      </div>
    </footer>
  );
}
