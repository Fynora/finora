import { Link } from 'react-router-dom';
import { INLINE_LINK, PublicLayout } from '../components/PublicLayout';
import { HelpContactCard } from './HelpContactCard';
import { GetTheApp } from '../components/GetTheApp';
import { STATEMENT_CODES, STATEMENT_CODES_TITLE, statementCodePath } from './statementCodes';

/** /bank-statement-codes: every code page, each with its one-line answer, so the index itself answers. */
export default function StatementCodeIndex() {
  return (
    <PublicLayout
      title={STATEMENT_CODES_TITLE}
      subtitle="What the short codes at the start of a bank statement line mean."
      description="What the codes on an Indian bank statement mean: NACH, ACH D, ECS, IMPS, NEFT, UTR, UPI, POS, ATM WDL, INT PD, BY CLG, TPT and more."
    >
      <ul className="space-y-3 mb-10">
        {STATEMENT_CODES.map((code) => (
          <li key={code.slug} className="bg-card border border-border rounded-xl p-5">
            <h2 className="font-semibold text-ink text-sm mb-1.5">
              <Link to={statementCodePath(code)} className="hover:text-primary hover:underline">{code.title}</Link>
            </h2>
            <p className="text-xs text-muted leading-relaxed">{code.description}</p>
          </li>
        ))}
      </ul>
      <p className="text-sm text-muted mb-10">
        Banks print their own abbreviations, so the legend on your statement, or your bank, is the final word on what a code
        means for your account. Questions about Fynora itself are answered in the{' '}
        <Link to="/help" className={INLINE_LINK}>Help Center</Link>.
      </p>
      <GetTheApp variant="full" placement="badge_bottom" className="mb-10" />
      <HelpContactCard />
    </PublicLayout>
  );
}
