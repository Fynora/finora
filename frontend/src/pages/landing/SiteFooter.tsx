import { Link } from 'react-router-dom';
import { ArrowUpRight } from 'lucide-react';
import { Logo } from './Nav';
import { footer } from './landing-config';
import { PLAY_STORE_URL } from '../../lib/openInApp';
import playStoreQr from '../../assets/landing/play-store-qr.svg';

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
  { title: 'Product', links: [['How it works', '#how'], ['What it does', '#features'], ['Ask Fyn', '#ask-fyn'], ['Security', '#security'], ['Pricing', '#pricing'], ['FAQ', '#faq']] },
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
 * The QR code is a static SVG (assets/landing/play-store-qr.svg) that encodes PLAY_STORE_URL
 * exactly. It was generated offline with the `qrcode` package (error correction M, 4-module quiet
 * zone) and checked by decoding the rendered image. It does not follow the constant: if
 * ANDROID_PACKAGE in lib/openInApp.ts ever changes, regenerate the SVG for the new URL and decode
 * it again before shipping, or the code will send people to a dead listing. Only the Android app
 * is on a store (Everywhere.tsx says so); an App Store badge belongs here the day an iOS listing
 * exists, not before.
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
            <div className="flex flex-wrap items-start gap-x-4 gap-y-4">
              {/* A phone cannot scan its own screen, so the code only appears from tablet width
                  up; on a phone the button is the whole call to action. The white tile is the
                  SVG's own quiet zone, which a scanner needs, so it is not cropped or tinted. */}
              <div className="hidden md:block shrink-0 text-center">
                <img
                  src={playStoreQr}
                  alt={footer.qrAlt}
                  width={112}
                  height={112}
                  className="rounded-lg"
                />
                <p className="text-[11px] mt-1.5 text-slate-400">{footer.qrCaption}</p>
              </div>
              <div>
                <a
                  href={PLAY_STORE_URL}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="m-tap inline-flex items-center gap-1.5 whitespace-nowrap rounded-lg bg-white px-3.5 py-2 text-[13px] font-semibold text-slate-900 hover:bg-slate-100 transition-colors"
                >
                  {footer.playStoreCta} <ArrowUpRight size={15} aria-hidden="true" />
                </a>
                <p className="text-[11px] mt-3 max-w-[200px] leading-snug text-slate-400">{footer.trademark}</p>
              </div>
            </div>
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
