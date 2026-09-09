import { Link } from 'react-router-dom';
import { PublicLayout, PublicSection } from '../components/PublicLayout';
import { SUPPORT_EMAIL, SUPPORT_MAILTO } from '../lib/contact';

export default function Terms() {
  return (
    <PublicLayout
      title="Terms & Conditions"
      subtitle="Last updated: September 2026. Please read these terms carefully before using Fynora."
    >
      <PublicSection title="1. Acceptance of Terms">
        <p>
          By creating an account or otherwise using Fynora ("the Service"), you agree to be bound by these
          Terms & Conditions. If you do not agree to these terms, please do not use the Service.
        </p>
      </PublicSection>

      <PublicSection title="2. Who You're Contracting With">
        <p>
          Fynora is operated by Fynora Technovation LLP, a limited liability partnership registered in India,
          with its registered office at 463, Sita Ram Compound, Chaman Ganj, Sipri Bazaar, Jhansi, Uttar
          Pradesh, 284003, India. References to "Fynora," "we," "us," or "our" in these terms mean Fynora
          Technovation LLP.
        </p>
      </PublicSection>

      <PublicSection title="3. User Responsibilities">
        <p>
          You are responsible for the accuracy of the information you provide, including account details,
          transaction data, and any bank or card statements you choose to import. Fynora helps you organize
          and understand your own financial data — it does not verify the accuracy of the underlying source
          documents on your behalf.
        </p>
      </PublicSection>

      <PublicSection title="4. Account Registration">
        <p>
          To use Fynora, you must register with a valid email address and mobile number. You agree to provide
          accurate, current information and to keep it up to date. You may not register on behalf of someone
          else without their permission, and you may not maintain more than one account per person.
        </p>
      </PublicSection>

      <PublicSection title="5. Eligibility">
        <p>
          Fynora is intended for users 18 years of age or older. By registering, you represent that you are at
          least 18. Fynora does not knowingly collect personal data from anyone under 18 — see our{' '}
          <Link to="/privacy" className="text-primary hover:underline">Privacy Policy</Link> for how to report
          this if you believe it has happened.
        </p>
      </PublicSection>

      <PublicSection title="6. Account Security">
        <p>
          You are responsible for maintaining the confidentiality of your password and for all activity that
          occurs under your account. Notify us immediately if you suspect unauthorized access. Fynora stores
          passwords using industry-standard hashing and never stores them in plain text.
        </p>
      </PublicSection>

      <PublicSection title="7. Acceptable Use">
        <p>You agree not to:</p>
        <ul className="list-disc list-inside space-y-1.5 ml-1">
          <li>Use the Service for any unlawful purpose or in violation of any applicable regulation</li>
          <li>Attempt to gain unauthorized access to another user's account or data</li>
          <li>Upload files containing malicious code or attempt to disrupt the Service's operation</li>
          <li>Reverse-engineer, scrape, or resell access to the Service without written permission</li>
        </ul>
      </PublicSection>

      <PublicSection title="8. Data Processing">
        <p>
          When you import a bank or credit card statement, Fynora processes that file to extract transactions,
          detect accounts, and generate categorization suggestions. This processing happens so the Service can
          function — see our <Link to="/privacy" className="text-primary hover:underline">Privacy Policy</Link> for
          full detail on what is collected, how it's stored, and your rights over it.
        </p>
      </PublicSection>

      <PublicSection title="9. Accuracy of Automated Features">
        <p>
          Fynora's transaction categorization, Financial Health Score, budgets, insights, and any other
          automated analysis are generated from the data you provide and Fynora's own rule-based logic — they
          are estimates intended to help you understand your own finances, not verified or audited figures.
          Categorization suggestions can be wrong, and a statement's extracted values (dates, balances,
          amounts) depend on the quality and format of the document you upload; always check anything you rely
          on against your bank's or card issuer's own statement or app. Fynora does not use third-party AI
          services (such as OpenAI, Anthropic, or Google Gemini) to process your financial data — this
          processing is performed by Fynora's own application logic.
        </p>
      </PublicSection>

      <PublicSection title="10. Service Availability">
        <p>
          Fynora is provided on an "as available" basis. We aim for reliable uptime but do not guarantee the
          Service will be uninterrupted, error-free, or available at all times, and we may modify, suspend, or
          discontinue any feature (with reasonable notice where practical) as the product evolves.
        </p>
      </PublicSection>

      <PublicSection title="11. Subscription & Billing">
        <p>
          Fynora offers Free, Plus, and Premium plans. Plus and Premium are paid subscriptions, billed on a
          recurring monthly or yearly cycle depending on the plan you choose — through Razorpay on the web, and
          through the App Store or Google Play on iOS/Android. By subscribing you authorize Fynora (or the
          relevant app store) to charge your chosen payment method each billing cycle until you cancel. See our{' '}
          <Link to="/refund-policy" className="text-primary hover:underline">Refund & Cancellation Policy</Link>{' '}
          for how to cancel and what happens to billing and access when you do.
        </p>
        <p>
          We may change subscription prices from time to time. For an existing subscriber, a price change takes
          effect no earlier than your next renewal after we've given you reasonable advance notice; continuing
          your subscription past that point means you accept the new price.
        </p>
      </PublicSection>

      <PublicSection title="12. Financial Advice Disclaimer">
        <p>
          Fynora is a personal finance organization tool, not a bank, financial advisor, broker, or investment
          adviser. Nothing in the Service — including budgets, insights, the Financial Health Score, or any
          other feature — constitutes financial, investment, tax, or legal advice, and it should not be relied
          on as a substitute for advice from a licensed professional.
        </p>
      </PublicSection>

      <PublicSection title="13. Intellectual Property">
        <p>
          The Fynora name, logo, and the Service's underlying software are the property of Fynora and its
          licensors. Your own financial data — transactions, accounts, budgets, goals, and anything else you
          create or import — remains yours.
        </p>
      </PublicSection>

      <PublicSection title="14. Limitation of Liability">
        <p>
          Fynora is provided "as is," without warranty of any kind, and Fynora shall not be liable for any
          indirect, incidental, or consequential damages arising from your use of the Service, including any
          decision made in reliance on categorization, the Financial Health Score, or any other automated
          feature described in Section 9.
        </p>
      </PublicSection>

      <PublicSection title="15. Account Termination">
        <p>
          You may stop using the Service and request account deletion at any time. Fynora may suspend or
          terminate accounts that violate these terms, engage in fraudulent activity, or pose a security risk
          to the Service or other users.
        </p>
      </PublicSection>

      <PublicSection title="16. Governing Law & Jurisdiction">
        <p>
          These terms are governed by the laws of India, without regard to conflict-of-law principles. Any
          disputes arising from these terms or your use of the Service shall be subject to the exclusive
          jurisdiction of the courts of Jhansi, Uttar Pradesh, India.
        </p>
      </PublicSection>

      <PublicSection title="17. Contact Information">
        <p>
          Questions about these terms can be sent to{' '}
          <a href={SUPPORT_MAILTO} className="text-primary hover:underline">{SUPPORT_EMAIL}</a>,
          or see <Link to="/contact" className="text-primary hover:underline">Contact Us</Link>.
        </p>
      </PublicSection>
    </PublicLayout>
  );
}
