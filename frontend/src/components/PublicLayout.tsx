import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ArrowLeft, Sparkles } from 'lucide-react';
import { BrandMark } from './BrandMark';

/**
 * Shared shell for the public/legal pages linked from Landing.tsx's footer (Terms, Privacy,
 * About, Careers, Help Center). Uses the app's real theme tokens (bg/card/ink/muted/primary --
 * the same ones AuthEntry.tsx and the dashboard use), so these pages follow the same light/dark
 * toggle as the rest of the app instead of a fixed-dark palette.
 */
export function PublicLayout({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <div className="min-h-screen bg-bg text-ink">
      <header className="sticky top-0 z-30 bg-bg/90 backdrop-blur border-b border-border">
        <div className="max-w-4xl mx-auto px-6 py-4 flex items-center justify-between">
          <Link to="/" className="flex items-center gap-2">
            <BrandMark size={32} variant="auto" className="rounded-lg" />
            <span className="font-extrabold tracking-wide text-ink">Fynora</span>
          </Link>
          <Link to="/" className="flex items-center gap-1.5 text-sm text-muted hover:text-ink transition-colors">
            <ArrowLeft size={15} /> Back to home
          </Link>
        </div>
      </header>

      <section className="border-b border-border">
        <div className="max-w-4xl mx-auto px-6 pt-16 pb-10">
          <span className="inline-flex items-center gap-1.5 text-xs font-medium text-primary bg-primary-light rounded-full px-3 py-1 mb-4">
            <Sparkles size={12} /> Fynora
          </span>
          <h1 className="text-3xl md:text-4xl font-extrabold text-ink mb-3">{title}</h1>
          {subtitle && <p className="text-muted text-base max-w-2xl">{subtitle}</p>}
        </div>
      </section>

      <main className="max-w-4xl mx-auto px-6 py-14">{children}</main>

      <footer className="border-t border-border">
        <div className="max-w-4xl mx-auto px-6 py-8 flex flex-col sm:flex-row justify-between items-center gap-3 text-xs text-muted">
          <span>© {new Date().getFullYear()} Fynora Technovation LLP. Not a bank. Not investment advice.</span>
          <div className="flex items-center gap-4">
            <Link to="/terms" className="hover:text-ink">Terms</Link>
            <Link to="/privacy" className="hover:text-ink">Privacy</Link>
            <Link to="/refund-policy" className="hover:text-ink">Refunds</Link>
            <Link to="/shipping-policy" className="hover:text-ink">Shipping</Link>
            <Link to="/contact" className="hover:text-ink">Contact</Link>
            <Link to="/about" className="hover:text-ink">About</Link>
            <Link to="/help" className="hover:text-ink">Help</Link>
          </div>
        </div>
      </footer>
    </div>
  );
}

/** A titled prose section — consistent spacing/typography for every legal/info page built on
 *  PublicLayout, so Terms/Privacy/About don't each reinvent heading styles. */
export function PublicSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="mb-10">
      <h2 className="text-xl font-bold text-ink mb-3">{title}</h2>
      <div className="text-sm text-muted leading-relaxed space-y-3">{children}</div>
    </section>
  );
}
