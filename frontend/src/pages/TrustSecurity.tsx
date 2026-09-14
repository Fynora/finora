import { Link } from 'react-router-dom';
import { PublicLayout, PublicSection } from '../components/PublicLayout';

// Trust & Security (issue #1453). Deliberately short and narrow: Privacy.tsx already covers
// encryption, the full infrastructure/vendor list, data retention, and administrative access in
// legal (DPDP Act) detail -- restating that here risks the two documents drifting apart over
// time. This page covers only what Privacy.tsx doesn't: backup/recovery status and Ask Fyn's real
// limitations, then links out to Privacy.tsx and Terms.tsx for everything else. Every claim below
// was checked against the current codebase or confirmed directly by the team before being
// written -- see this ticket's own PR for what was verified and how.
export default function TrustSecurity() {
  return (
    <PublicLayout
      title="Trust & Security"
      subtitle="What actually happens to your data, in plain terms -- not what sounds reassuring."
    >
      <PublicSection title="Backups & recovery">
        <p>
          Your data lives in a managed PostgreSQL database with point-in-time recovery enabled --
          if something ever goes wrong at the infrastructure level, your account and transaction
          history can be restored to a specific moment in time, not just to the last full backup.
        </p>
      </PublicSection>

      <PublicSection title="Ask Fyn's limitations">
        <p>
          Ask Fyn, Fynora's AI assistant, only answers using your own data already stored in
          Finora -- your balance, budget status, recent transactions, and spending by category --
          retrieved through a fixed set of lookups each time you ask a question. It cannot take
          any action on your account: it has no way to create, edit, or delete anything.
        </p>
        <p>
          It's instructed to state only numbers it actually retrieved, never to guess or estimate
          one, and it isn't a financial advisor -- asked for investment advice or a recommendation
          about a future financial decision, it declines and suggests a licensed advisor instead.
          Like every other action on your account, conversations with Fyn are logged, and access
          can be disabled instantly if something needs investigating. See{' '}
          <Link to="/privacy" className="text-primary hover:underline">Privacy Policy</Link> for
          exactly what's sent to Anthropic's Claude API to generate a response.
        </p>
      </PublicSection>

      <PublicSection title="Everything else">
        <p>
          Encryption, our full list of infrastructure and service providers, how long your data is
          kept, who at Fynora can access it and when, and what happens if you delete your account
          are all covered in detail in our{' '}
          <Link to="/privacy" className="text-primary hover:underline">Privacy Policy</Link>. Account
          terms and acceptable use are in our{' '}
          <Link to="/terms" className="text-primary hover:underline">Terms of Service</Link>.
        </p>
      </PublicSection>
    </PublicLayout>
  );
}
