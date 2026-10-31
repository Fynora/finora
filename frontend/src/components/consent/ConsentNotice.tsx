import { Link } from 'react-router-dom';
import { GRIEVANCE_EMAIL, GRIEVANCE_MAILTO, GRIEVANCE_OFFICER_NAME } from '../../lib/contact';

/**
 * The standalone notice: what Fynora collects, why, how to withdraw, and where to complain
 * (privacy workstream 8, docs/superpowers/specs/2026-10-31-ai-consent-and-terms-acceptance-design.md).
 * Shown on the sign-up form and on the consent step. Draft wording for counsel to review before the
 * documents are published (workstream 11); every statement here matches the Privacy Policy as it
 * stands. The mobile app carries the same text (mobile/src/components/consent/ConsentNotice.tsx).
 */
export function ConsentNotice() {
  return (
    <div className="text-xs text-ink space-y-2 text-left" data-testid="consent-notice">
      <p className="font-medium">What Fynora uses, and why</p>
      <ul className="list-disc pl-4 space-y-1 text-muted">
        <li>Your name, email and mobile number — to create your account, sign you in and verify your phone.</li>
        <li>
          The accounts, statements and transactions you add or import — to show your dashboards, budgets and
          reports. Fynora never sells them or uses them for advertising.
        </li>
        <li>Sign-in and device details (time, IP address, device or browser) — to keep your account secure.</li>
        <li>For a sign-up on the website, the site or campaign link you came from — to learn what brings people to Fynora.</li>
      </ul>
      <p className="font-medium">Optional: AI features</p>
      <p className="text-muted">
        Ask Fyn, AI insights and AI category suggestions send the question you ask, the account figures
        needed to answer it, or the description of a transaction you add by hand to Anthropic (Claude).
        Names and account numbers Fynora recognises are hidden first. They stay off unless you tick the box
        below.
      </p>
      <p className="font-medium">Your choices</p>
      <p className="text-muted">
        Turn AI features on or off at any time in Settings → Data. You can also export your data or delete
        your account there; deleting it is how you withdraw consent to everything else.
      </p>
      <p className="text-muted">
        Questions or complaints: {GRIEVANCE_OFFICER_NAME}, Grievance Officer,{' '}
        <a href={GRIEVANCE_MAILTO} className="text-primary font-medium">{GRIEVANCE_EMAIL}</a>. If you are not
        satisfied with the answer, you can complain to the Data Protection Board of India. Full details:{' '}
        <Link to="/privacy" target="_blank" rel="noopener noreferrer" className="text-primary font-medium">Privacy Policy</Link>.
      </p>
    </div>
  );
}
