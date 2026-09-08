import { PublicLayout, PublicSection } from '../components/PublicLayout';
import { SUPPORT_EMAIL, SUPPORT_MAILTO, GRIEVANCE_EMAIL, GRIEVANCE_MAILTO, GRIEVANCE_OFFICER_NAME } from '../lib/contact';

export default function Privacy() {
  return (
    <PublicLayout
      title="Privacy Policy"
      subtitle="Last updated: September 2026. This explains what Fynora collects, why, and the rights you have over it under India's Digital Personal Data Protection Act, 2023 (DPDP Act)."
    >
      <PublicSection title="Who We Are & Your Consent">
        <p>
          Fynora is operated by Fynora Technovation LLP, registered at 463, Sita Ram Compound, Chaman Ganj,
          Sipri Bazaar, Jhansi, Uttar Pradesh, 284003, India. Under the DPDP Act, Fynora Technovation LLP is
          the "Data Fiduciary" for the personal data described in this policy, and you are the "Data
          Principal." We process your personal data on the basis of the consent you give when you create an
          account and when you take actions in the app that involve sharing further data (such as importing a
          statement) — described in plain terms in the sections below, as the Act requires. You may withdraw
          consent at any time; since Fynora's core features depend on ongoing access to the data you've
          provided, withdrawing consent in practice means deleting your account (see Data Deletion below).
          Withdrawal does not affect the lawfulness of processing carried out before you withdrew it.
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
          In most cases statements are processed entirely automatically by Fynora's own rule-based extraction
          logic — Fynora does not send your statement or its contents to third-party AI services such as
          OpenAI, Anthropic, or Google Gemini. When an import cannot be processed automatically, it is queued
          for review, and authorized staff may access the statement to diagnose and fix the problem. Every
          such access is logged and auditable; see Administrative Access below.
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
          and financial insights (via Fynora's own rule-based logic, not a third-party AI service — see
          Uploaded Statements above); detect duplicate or transfer transactions; and secure your account
          (fraud/lockout detection on repeated failed logins).
        </p>
      </PublicSection>

      <PublicSection title="Infrastructure & Service Providers">
        <p>Fynora runs on the following infrastructure and service providers, each processing only what its function requires:</p>
        <ul className="list-disc pl-5 space-y-1">
          <li><strong className="text-gray-300">Firebase Authentication</strong> (Google) — sign-in and identity verification.</li>
          <li><strong className="text-gray-300">Railway PostgreSQL</strong> — our primary database.</li>
          <li><strong className="text-gray-300">Cloudflare R2</strong> — storage for the original statement files you upload.</li>
          <li><strong className="text-gray-300">Railway</strong> — backend application hosting.</li>
          <li><strong className="text-gray-300">Cloudflare</strong> — frontend/website hosting and edge security.</li>
          <li><strong className="text-gray-300">Resend</strong> — transactional email delivery (verification, password reset, notifications).</li>
          <li><strong className="text-gray-300">TwoFactor</strong> — SMS/OTP delivery for phone verification.</li>
        </ul>
        <p>
          Some of these providers operate outside India — see Cross-Border Data Transfer below for how that's
          handled under the DPDP Act.
        </p>
      </PublicSection>

      <PublicSection title="Data Encryption & Security">
        <p>
          Passwords are hashed with bcrypt and never stored or logged in plain text. Password reset tokens and
          session refresh tokens are stored hashed, not in plain text, so a database compromise alone cannot be
          used to reset an account or hijack a session. Sessions are scoped per device and can be individually
          revoked; a sign-in session (refresh token) expires after 30 days of use, and the short-lived access
          token behind it expires every 15 minutes. All traffic between your device and Fynora's servers is
          encrypted in transit (HTTPS/TLS), and our website enforces HTTP Strict Transport Security (HSTS).
        </p>
        <p>
          Where we hold a live credential to an external account on your behalf — currently, a connected
          Gmail account's access token — it is encrypted at rest with a dedicated application-level encryption
          key (AES-256), separate from and unrelated to your login password. Your other financial data
          (transactions, accounts, statements, budgets, goals) is protected by the security of the underlying
          infrastructure listed above (Railway, Cloudflare R2), rather than by an additional layer of
          Fynora-managed encryption on top of it.
        </p>
      </PublicSection>

      <PublicSection title="Administrative Access">
        <p>
          Access to customer data by Fynora staff is governed by role-based permissions — a staff member only
          has access to the specific data their role requires, not blanket access to all customer data. Every
          administrative access to your data is logged and auditable, and access is permitted only where
          necessary to operate and support the Service (for example, reviewing a statement that failed
          automated processing — see Uploaded Statements above).
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
          Your data is retained for as long as your account is active — Fynora does not automatically delete
          statements, transactions, or other data from an active account. If you delete an individual account
          (a bank/card/investment account you've added within Fynora), it and its statements continue to
          appear in Statement History for 7 days before being dropped from that view, purely so recent context
          isn't lost abruptly; there is no separate "undo delete" action.
        </p>
        <p>
          We maintain routine backups of our production database for disaster recovery, with point-in-time
          recovery enabled. Because of this, a small amount of data may persist in encrypted backups for a
          limited period after you delete it from the live system, until those backups themselves cycle out —
          this is standard practice and does not mean the data remains accessible or in active use.
        </p>
        <p>
          Security event logs (audit logs) of account activity are kept indefinitely for security purposes,
          but the detailed content of each event is cleared after 730 days, leaving only a minimal record
          (who, what, when) for as long as the log entry itself exists.
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
            data — directly in the app (Settings, Accounts, Transactions) for most fields, or by contacting{' '}
            <a href={SUPPORT_MAILTO} className="text-primary hover:underline">{SUPPORT_EMAIL}</a>.
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
            your behalf in the event of your death or incapacity, by contacting{' '}
            <a href={SUPPORT_MAILTO} className="text-primary hover:underline">{SUPPORT_EMAIL}</a>.
          </li>
          <li>
            <strong className="text-gray-300">Grievance redressal</strong> — see below — and, if unresolved,
            the right to file a complaint with the Data Protection Board of India.
          </li>
        </ul>
      </PublicSection>

      <PublicSection title="Grievance Redressal">
        <p>
          If you have a complaint about how Fynora handles your personal data, contact our Grievance Officer,
          {' '}{GRIEVANCE_OFFICER_NAME}, at{' '}
          <a href={GRIEVANCE_MAILTO} className="text-primary hover:underline">{GRIEVANCE_EMAIL}</a>{' '}
          with the details of your concern. We aim to acknowledge and resolve grievances within 30 days. If
          you're not satisfied with the outcome, you may escalate the complaint to the Data Protection Board
          of India.
        </p>
      </PublicSection>

      <PublicSection title="Data Export">
        <p>
          You can export your Fynora data at any time from Settings, on both the web app and the mobile app.
          Your export downloads as a ZIP archive containing your accounts, transactions, budgets, goals, and
          other data as JSON files, plus the original statement files you uploaded in their original format,
          along with a manifest listing exactly what's included (and, for transparency, what's deliberately
          excluded and why). Exporting requires you to re-confirm your password (or Google/Apple sign-in), the
          same as account deletion.
        </p>
      </PublicSection>

      <PublicSection title="Data Deletion">
        <p>
          You can delete individual transactions, accounts, or statements at any time from within Fynora. You
          can also delete your entire Fynora account yourself, from Settings on either the web app or the
          mobile app — this requires you to re-confirm your password (or Google/Apple sign-in) plus a one-time
          code sent to you, as a safeguard against someone else deleting your account without your knowledge.
        </p>
        <p>
          Account deletion is immediate and permanent once confirmed — there is no waiting period and no
          self-service way to undo it. Your personal information is erased or anonymized and your financial
          data is permanently removed at that point. Two narrow exceptions: (1) the original filename of a
          statement you uploaded, and the account-holder name Fynora automatically detected on it, may be
          retained internally after deletion rather than erased, as they can also serve as parsing-accuracy
          records unrelated to any one user; and (2) as described under Data Retention above, a copy may
          briefly persist in an encrypted backup until that backup cycles out. If you'd rather not go through
          the in-app flow, you can also request deletion by contacting{' '}
          <a href={SUPPORT_MAILTO} className="text-primary hover:underline">{SUPPORT_EMAIL}</a>.
        </p>
      </PublicSection>

      <PublicSection title="Third-Party Services">
        <p>
          Fynora does not sell your data to third parties. Each infrastructure and service provider listed
          above under Infrastructure & Service Providers receives only the minimum information needed to
          perform its specific function (for example, an email provider receives your email address and
          message content, not your transaction history).
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
