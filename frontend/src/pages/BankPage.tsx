import { useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, ChevronRight } from 'lucide-react';
import { INLINE_LINK, PublicLayout } from '../components/PublicLayout';
import { GetTheApp } from '../components/GetTheApp';
import NotFound from './NotFound';
import { hero } from './landing/landing-config';
import {
  type BankPage as BankPageData,
  BANKS_HUB_PATH,
  BANKS_HUB_TITLE,
  FREE_PLAN_LIMIT,
  bankPageBySlug,
  bankPageFaq,
  notAffiliatedLine,
} from './bankPages';

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

/** '2026-10-31' -> '31 October 2026', the same in the browser and the prerendered HTML (no locale). */
export function longDate(iso: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  if (!match) throw new Error(`BankPage: ${JSON.stringify(iso)} is not a yyyy-mm-dd date`);
  return `${Number(match[3])} ${MONTHS[Number(match[2]) - 1]} ${match[1]}`;
}

/**
 * What happens after an upload, the same on every bank page. Each step is current app behaviour:
 * the checks are the validators and TrustPredicate, confirm-before-save is the import review, and
 * the held wording is the app's own HELD_DETAIL (lib/importJob.ts).
 */
export const AFTER_UPLOAD_STEPS = [
  'Fynora reads every transaction and the details the statement prints.',
  'Where the statement prints balances or totals, Fynora compares its reading with them.',
  'You see every row and confirm before anything is saved. Rows that look like duplicates of something already imported are flagged for you to keep or skip.',
  "If the amounts don't agree, or a transaction looks missing, the statement is held instead of imported. A person checks it by hand within 48 hours, and you're notified when it's done.",
];

/**
 * One bank's statement page, /banks/<slug>. The direct answer comes first, under the <h1>, then how
 * to download the statement from the bank's own site or app (each step paraphrased from the bank's
 * page, linked and dated), what Fynora reads from it, what happens after upload, and the FAQ that
 * is also the page's FAQPage markup (bankPageFaq feeds both). The prerendered copy
 * (dist/banks/<slug>.html) is this same component.
 */
export function BankPageView({ page }: { page: BankPageData }) {
  const statementWord = page.product === 'credit-card' ? 'credit card statement' : 'statement';
  const faq = bankPageFaq(page);
  return (
    <PublicLayout title={page.title} description={page.description}>
      {/* Two columns from laptop width: the page, and a sticky "Get Fynora on Android" card with the
          QR code beside it (the column the max-w-2xl text left empty). Narrower, the card is the
          compact strip after the answer instead, and both shapes are in the HTML. */}
      <div className="lg:grid lg:grid-cols-[minmax(0,1fr)_184px] lg:gap-8">
        <div className="min-w-0">
          <article className="mb-10 space-y-4 max-w-2xl">
            <p className="text-base text-ink leading-relaxed">{page.answer}</p>
            <p className="text-xs text-muted leading-relaxed" data-testid="not-affiliated">{notAffiliatedLine(page.bankName)}</p>
          </article>

          <GetTheApp variant="compact" placement="badge_top" className="mb-10 max-w-2xl lg:hidden" />

          <section className="mb-10 max-w-2xl">
            <h2 className="text-lg font-semibold text-ink mb-3">How to download your {page.bankName} {statementWord}</h2>
            {page.noStepsNote && <p className="text-sm text-ink leading-relaxed mb-3">{page.noStepsNote}</p>}
            {page.steps.map((group) => (
              <div key={group.heading} className="mb-4">
                <h3 className="text-sm font-semibold text-ink mb-2">{group.heading}</h3>
                <ol className="list-decimal pl-5 space-y-1 text-sm text-ink leading-relaxed">
                  {group.steps.map((step) => <li key={step}>{step}</li>)}
                </ol>
              </div>
            ))}
            <p className="text-xs text-muted leading-relaxed">
              {page.sources.length === 1 ? 'Source' : 'Sources'}:{' '}
              {page.sources.map((source, i) => (
                <span key={source.url}>
                  {i > 0 && '; '}
                  <a href={source.url} className={INLINE_LINK} rel="noopener noreferrer" target="_blank">{source.label}</a>
                  {' '}(read {longDate(source.checked)})
                </span>
              ))}
              . Steps can change; the bank's own page is the authority.
            </p>
          </section>

          <section className="mb-10 max-w-2xl">
            <h2 className="text-lg font-semibold text-ink mb-3">What Fynora reads from your {page.bankName} {statementWord}</h2>
            <ul className="list-disc pl-5 space-y-1 text-sm text-ink leading-relaxed">
              {page.reads.map((item) => <li key={item}>{item}</li>)}
            </ul>
            {(page.notes ?? []).map((note) => <p key={note} className="text-sm text-ink leading-relaxed mt-3">{note}</p>)}
            <p className="text-xs text-muted mt-3">Last checked against a real {page.bankName} {statementWord} on {longDate(page.measuredOn)}.</p>
          </section>

          <section className="mb-10 max-w-2xl">
            <h2 className="text-lg font-semibold text-ink mb-3">What happens after you upload</h2>
            <ol className="list-decimal pl-5 space-y-1 text-sm text-ink leading-relaxed">
              {AFTER_UPLOAD_STEPS.map((step) => <li key={step}>{step}</li>)}
            </ol>
            <ul className="list-disc pl-5 space-y-1 text-sm text-ink leading-relaxed mt-4">
              <li>Free plan: {FREE_PLAN_LIMIT!.charAt(0).toLowerCase()}{FREE_PLAN_LIMIT!.slice(1)}.</li>
              <li>Fynora reads PDF and CSV files. Excel files can't be uploaded.</li>
            </ul>
            <p className="mt-5">
              {/* The app's own primary button, not the landing page's m-btn-primary: that one reads
                  --m-brand, which only exists inside the landing page's .marketing wrapper, so here
                  it had no background and its white text vanished on the light theme. */}
              <Link
                to="/auth"
                className="inline-flex items-center bg-primary hover:bg-primary-dark text-on-primary rounded-lg px-4 py-2.5 text-sm font-semibold"
              >
                {hero.primaryCta}
              </Link>
            </p>
          </section>

          {/* From laptop width the sidebar already shows this page's QR code, so this card does not. */}
          <GetTheApp variant="full" placement="badge_bottom" className="mb-10 max-w-2xl" qrClassName="hidden md:block lg:hidden" />

          <section className="mb-10 max-w-2xl">
            <h2 className="text-lg font-semibold text-ink mb-3">Questions</h2>
            <dl className="space-y-4">
              {faq.map(([question, answer]) => (
                <div key={question}>
                  <dt className="text-sm font-semibold text-ink">{question}</dt>
                  <dd className="text-sm text-ink leading-relaxed mt-1">{answer}</dd>
                </div>
              ))}
            </dl>
          </section>

          <nav aria-label="Breadcrumb" className="mb-10 text-xs text-muted">
            <ol className="flex flex-wrap items-center gap-1.5">
              <li><Link to={BANKS_HUB_PATH} className="text-primary hover:underline font-medium">{BANKS_HUB_TITLE}</Link></li>
              <li aria-hidden="true"><ChevronRight size={12} /></li>
              <li>{page.title}</li>
            </ol>
          </nav>

          <p className="mb-6">
            <Link to={BANKS_HUB_PATH} className="inline-flex items-center gap-1.5 text-sm text-muted hover:text-ink">
              <ArrowLeft size={14} /> All bank statements
            </Link>
          </p>
        </div>
        <div className="hidden lg:block">
          <GetTheApp variant="sidebar" placement="sidebar" className="sticky top-24" />
        </div>
      </div>
    </PublicLayout>
  );
}

/** The /banks/:slug route. A slug with no page is the not-found page, like any unknown address. */
export default function BankPage() {
  const { slug } = useParams();
  const page = bankPageBySlug(slug);
  useEffect(() => {
    window.scrollTo(0, 0);
  }, [slug]);
  return page ? <BankPageView page={page} /> : <NotFound />;
}
