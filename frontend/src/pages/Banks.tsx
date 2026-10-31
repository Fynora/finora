import { Link } from 'react-router-dom';
import { PublicLayout } from '../components/PublicLayout';
import { BANK_PAGES, BANKS_HUB_TITLE, bankPagePath, type BankPage } from './bankPages';

function PageList({ heading, pages }: { heading: string; pages: BankPage[] }) {
  return (
    <section className="mb-8">
      <h2 className="text-lg font-semibold text-ink mb-3">{heading}</h2>
      <ul className="space-y-2">
        {pages.map((page) => (
          <li key={page.slug}>
            <Link to={bankPagePath(page)} className="text-sm text-primary hover:underline">{page.title}</Link>
          </li>
        ))}
      </ul>
    </section>
  );
}

/**
 * /banks: every bank statement page, savings first, then credit cards, in BANK_PAGES order. A fixed
 * prerendered page (scripts/ssr-entry.tsx `routes`), so every bank page is one link from the footer.
 */
export default function Banks() {
  const savings = BANK_PAGES.filter((p) => p.product === 'savings');
  const cards = BANK_PAGES.filter((p) => p.product === 'credit-card');
  return (
    <PublicLayout
      title={BANKS_HUB_TITLE}
      description="How to download your bank or credit card statement from Indian banks, and what Fynora reads from it once you upload the PDF."
    >
      <div className="max-w-2xl space-y-4 mb-10">
        <p className="text-base text-ink leading-relaxed">
          Each page below covers one bank's statement: how to download it from the bank's own website or app, and what
          Fynora reads from it. Every one was checked against a real statement from that bank. Statements from other
          Indian banks can be uploaded too, and Fynora compares its reading with whatever balances and totals they print.
        </p>
        <p className="text-xs text-muted leading-relaxed">
          Fynora is an independent app. It is not affiliated with, endorsed by or partnered with any bank named here.
          Banks are named only to describe their statements.
        </p>
      </div>
      <PageList heading="Bank account statements" pages={savings} />
      <PageList heading="Credit card statements" pages={cards} />
    </PublicLayout>
  );
}
