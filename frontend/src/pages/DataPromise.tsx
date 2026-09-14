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
//
// Deliberately scoped to EXPORT, not to "financial history" in general: plans.ts markets a real
// Plus/Premium-only "Extended financial history" feature, enforced by ImportService's
// FREE_STATEMENT_PERIOD_MAX_DAYS (31 days per statement on Free, via the EXTENDED_HISTORY
// entitlement). That's a real, shipped, tiered limit on how much history a Free account can bring
// in per import -- a blanket "your financial history is never leverage" claim would contradict it.
// The promise here is narrower and still fully true: whatever is already in your account exports
// in full, on every plan.
//
// The re-auth comparison is to account DEACTIVATION, not deletion: ExportDataRequest and
// DeactivateRequest share the exact same fields (currentPassword/googleIdToken/appleIdToken, no
// OTP) -- AccountLifecycleDtos.java's own doc comment says so explicitly ("same bar as
// DeactivateRequest's, not the OTP tier"). DeleteAccountRequest is a stricter, separate,
// OTP-gated flow, so comparing export's safeguard to deletion would overstate it.
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
          same feature available on Plus and Premium, not a smaller version of it. Whatever's
          already in your account is never something we hold onto as leverage to get you to
          upgrade — it's yours to take with you in full, on any plan.
        </p>
      </PublicSection>

      <PublicSection title="What you get">
        <p>
          Your export is a ZIP archive containing your accounts, transactions, budgets, goals, and
          other data as JSON files, plus the original statement files you uploaded, in their
          original format — along with a manifest listing exactly what's included, and what's
          deliberately excluded and why. It requires you to re-confirm your password (or your
          Google/Apple sign-in) first, the same safeguard deactivating your account uses. See{' '}
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
