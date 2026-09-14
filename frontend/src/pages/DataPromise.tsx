import { Link } from 'react-router-dom';
import { PublicLayout, PublicSection } from '../components/PublicLayout';

// Public Data Portability Promise (issue #1454). Checked against the actual code before writing
// a word of this, not assumed: UserController.exportData / DataExportService.buildBundle carry no
// entitlement check and no plan branching anywhere (grepped for FeatureEntitlement/plan code --
// none), and both frontend's ExportDataModal.tsx and mobile's ExportDataSheet.tsx call it
// unconditionally, with no plan-gated disabled state. The only limit on the endpoint is
// RateLimitFilter's dataExportLimiter (5/day, per IP) -- a uniform anti-abuse throttle, not a
// tier distinction, so it doesn't belong in a promise about paywalls. Mechanics (what's in the
// ZIP, the password re-confirmation) are already documented in Privacy.tsx's "Data Export"
// section -- this page states the promise and links there rather than restating it.
export default function DataPromise() {
  return (
    <PublicLayout
      title="Your Data, Always Yours"
      subtitle="Full export, every plan, no upgrade required."
    >
      <PublicSection title="The promise">
        <p>
          Every plan on Fynora, Free included, can export the full data in their account at any
          time. There's no reduced or partial export tier — the export available on Free is the
          same feature available on Plus and Premium, not a smaller version of it. Your financial
          history is never something we hold onto as leverage to get you to upgrade.
        </p>
      </PublicSection>

      <PublicSection title="What you get">
        <p>
          Your export is a ZIP archive containing your accounts, transactions, budgets, goals, and
          other data as JSON files, plus the original statement files you uploaded, in their
          original format — along with a manifest listing exactly what's included, and what's
          deliberately excluded and why. It requires you to re-confirm your password (or your
          Google/Apple sign-in) first, the same safeguard account deletion uses. See{' '}
          <Link to="/privacy" className="text-primary hover:underline">Privacy Policy</Link> for
          the full mechanics.
        </p>
      </PublicSection>

      <PublicSection title="Why we're saying this outright">
        <p>
          Financial software has a reputation for treating your own history as leverage — an
          export that's missing years, or locked behind the plan above the one you're on. Export
          isn't something Fynora sells you; it's something every account already has.
        </p>
      </PublicSection>
    </PublicLayout>
  );
}
