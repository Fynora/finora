import { PublicLayout, PublicSection } from '../components/PublicLayout';
import { SUPPORT_EMAIL, SUPPORT_MAILTO, GRIEVANCE_EMAIL, GRIEVANCE_MAILTO } from '../lib/contact';

export default function Privacy() {
  return (
    <PublicLayout
      title="Privacy Policy"
      subtitle="Last updated: September 2026. This explains what Fynora collects, why, and the rights you have over it under India's Digital Personal Data Protection Act, 2023 (DPDP Act)."
    >
      <PublicSection title="Data Fiduciary & Your Consent">
        <p>
          Under the DPDP Act, Fynora is the "Data Fiduciary" for the personal data described in this policy,
          and you are the "Data Principal." We process your personal data on the basis of the consent you give
          when you create an account and when you take actions in the app that involve sharing further data
          (such as importing a statement) — described in plain terms in the sections below, as the Act
          requires. You may withdraw consent at any time by deleting your account (see Data Deletion below);
          withdrawal does not affect the lawfulness of processing carried out before you withdrew it.
        </p>
      </PublicSection>

      <PublicSection title="Information We Collect">
        <p>
          Fynora collects the information you provide directly (registration details, account and transaction
          data you add or import) and a small amount of technical information needed to operate the Service
          securely (login timestamps, IP address at login, device/browser information for session security).
        </p>
      </PublicSection>

      <PublicSection title="Personal Information">
        <p>
          This includes your full name, email address, and mobile number, collected at registration and used
          for authentication (including email-or-phone login), account recovery, and OTP-based phone
          verification.
        </p>
      </PublicSection>

      <PublicSection title="Financial Data">
        <p>
          Fynora stores the accounts, transactions, budgets, goals, and categorization data you create or
          import. This data is used exclusively to power the features you use — dashboards, reports, budgets,
          and insights — and is never sold to third parties or used for advertising.
        </p>
      </PublicSection>

      <PublicSection title="Uploaded Statements">
        <p>
          When you import a bank or credit card statement, the original file is stored securely and linked to
          your account so you can re-download it or re-process it later from Statement History.
        </p>
        <p>
          In most cases statements are processed entirely automatically. When an import cannot be processed
          automatically, authorized staff may review statement information in order to diagnose and resolve
          the problem, and we may use AI tooling to help with that diagnosis.
        </p>
      </PublicSection>

      <PublicSection title="Cookies">
        <p>
          Fynora uses essential, session-related storage (such as your authentication token) to keep you
          signed in. We do not currently use third-party advertising or tracking cookies.
        </p>
      </PublicSection>

      <PublicSection title="Analytics">
        <p>
          We may collect aggregated, non-identifying usage data (such as which features are used most) to
          improve the product. This is never combined with your individual financial data for any purpose
          outside operating and improving Fynora itself.
        </p>
      </PublicSection>

      <PublicSection title="Data Usage">
        <p>
          Your data is used to: provide the core features you sign up for; generate categorization suggestions
          and AI insights; detect duplicate or transfer transactions; and secure your account (fraud/lockout
          detection on repeated failed logins).
        </p>
      </PublicSection>

      <PublicSection title="Data Encryption">
        <p>
          Passwords are hashed with bcrypt and never stored or logged in plain text. Password reset tokens are
          hashed before storage, so a database compromise alone cannot be used to reset an account. All traffic
          between your browser and Fynora's servers is encrypted in transit (HTTPS).
        </p>
      </PublicSection>

      <PublicSection title="Administrative Access">
        <p>
          Access to customer data by Fynora staff is restricted to authorized personnel, recorded and
          audited, and permitted only where it is necessary to operate and support the service.
        </p>
      </PublicSection>

      <PublicSection title="Data Breach Notification">
        <p>
          If a personal data breach occurs, we will notify the Data Protection Board of India and affected
          users as required under the DPDP Act, and take steps to contain and remediate the breach.
        </p>
      </PublicSection>

      <PublicSection title="Data Retention">
        <p>
          Your data is retained for as long as your account is active. Deleted accounts, transactions, and
          statements are soft-deleted first (recoverable for a short window — e.g. a deleted statement's
          associated account remains visible in Statement History for 7 days) before being permanently removed.
        </p>
      </PublicSection>

      <PublicSection title="Your Rights Under the DPDP Act">
        <p>As a Data Principal under the DPDP Act, you have the right to:</p>
        <ul className="list-disc pl-5 space-y-1">
          <li>
            <strong className="text-gray-300">Access</strong> a summary of the personal data Fynora holds
            about you and how it is being processed.
          </li>
          <li>
            <strong className="text-gray-300">Correct or update</strong> inaccurate or incomplete personal
            data — directly in the app (Settings, Accounts, Transactions) for most fields, or by contacting us.
          </li>
          <li>
            <strong className="text-gray-300">Erase</strong> personal data that is no longer needed for the
            purpose it was collected for — see Data Deletion below.
          </li>
          <li>
            <strong className="text-gray-300">Withdraw consent</strong> at any time, as easily as you gave it.
          </li>
          <li>
            <strong className="text-gray-300">Nominate</strong> another individual to exercise these rights on
            your behalf in the event of your death or incapacity, by contacting us.
          </li>
          <li>
            <strong className="text-gray-300">Grievance redressal</strong> — see below — and, if unresolved,
            the right to file a complaint with the Data Protection Board of India.
          </li>
        </ul>
      </PublicSection>

      <PublicSection title="Grievance Redressal">
        <p>
          If you have a complaint about how Fynora handles your personal data, contact our Grievance Officer
          at{' '}
          <a href={GRIEVANCE_MAILTO} className="text-primary hover:underline">{GRIEVANCE_EMAIL}</a>{' '}
          with the details of your concern. We aim to acknowledge and resolve grievances within 30 days. If
          you're not satisfied with the outcome, you may escalate the complaint to the Data Protection Board
          of India.
        </p>
      </PublicSection>

      <PublicSection title="Data Deletion">
        <p>
          You may delete individual transactions, accounts, or statements from within Fynora, or request full
          account deletion by contacting{' '}
          <a href={SUPPORT_MAILTO} className="text-primary hover:underline">{SUPPORT_EMAIL}</a>.
          Full account deletion removes your personal information and financial data from active systems.
        </p>
      </PublicSection>

      <PublicSection title="Third-Party Services">
        <p>
          Fynora does not sell your data to third parties. Where a third-party service is used (such as an
          email or SMS provider to deliver password reset links or OTP codes, a phone-verification provider
          for OTP-based sign-up, or the infrastructure providers described below), only the minimum
          information needed to deliver that function is shared.
        </p>
      </PublicSection>

      <PublicSection title="Cross-Border Data Transfer">
        <p>
          Fynora's application data is currently hosted and processed on servers located outside India (in
          the United States). Some third-party services we use for authentication and communications are
          also based outside India. The DPDP Act permits this kind of transfer except to countries the
          Government of India specifically restricts by notification; we do not transfer data to any such
          restricted country. Wherever your data is processed, it remains subject to the protections
          described in this policy.
        </p>
      </PublicSection>

      <PublicSection title="Children's Data">
        <p>
          Fynora is intended for users 18 years of age or older and is not directed at children. We do not
          knowingly collect personal data from anyone under 18. If you believe a child has provided us
          personal data, contact our Grievance Officer at{' '}
          <a href={GRIEVANCE_MAILTO} className="text-primary hover:underline">{GRIEVANCE_EMAIL}</a>{' '}
          and we will delete it.
        </p>
      </PublicSection>

      <PublicSection title="Policy Updates">
        <p>
          We may update this policy from time to time. Material changes will be reflected by an updated "Last
          updated" date at the top of this page.
        </p>
      </PublicSection>
    </PublicLayout>
  );
}
