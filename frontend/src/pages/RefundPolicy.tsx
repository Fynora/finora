import { Link } from 'react-router-dom';
import { PublicLayout, PublicSection } from '../components/PublicLayout';
import { SUPPORT_EMAIL, SUPPORT_MAILTO } from '../lib/contact';

export default function RefundPolicy() {
  return (
    <PublicLayout
      title="Refund & Cancellation Policy"
      subtitle="Last updated: September 2026. Applies to any paid Fynora subscription (Plus or Premium)."
    >
      <PublicSection title="Current Billing Status">
        <p>
          Fynora offers Free, Plus, and Premium plans. Plus and Premium are paid, billed on a recurring monthly
          or yearly cycle — on the web through Razorpay, and on iOS/Android through the App Store or Google Play.
          See{' '}
          <Link to="/terms" className="text-primary hover:underline">Terms & Conditions</Link> for the full
          subscription clause.
        </p>
      </PublicSection>

      <PublicSection title="Cancelling a Subscription">
        <p>
          <strong className="text-gray-300">On the web:</strong> cancel any time from the Billing page in your
          account. Cancellation takes effect at the end of your current billing cycle — you keep full access to
          paid features until then, and you will not be charged again after that cycle ends.
        </p>
        <p>
          <strong className="text-gray-300">On iOS or Android:</strong> a subscription purchased through the App
          Store or Google Play is managed there, not in the Fynora app — this is required by Apple's and Google's
          own store policies. Cancel it from your Apple ID subscription settings (iOS) or the Play Store's
          Subscriptions page (Android). Access continues, on the same end-of-cycle terms as above, until the
          store confirms the cancellation.
        </p>
      </PublicSection>

      <PublicSection title="Refunds">
        <p>
          <strong className="text-gray-300">Web subscriptions (Razorpay):</strong> Fynora does not offer refunds
          for partial billing periods or unused time within a cycle you've already paid for. If you cancel
          partway through a cycle, you retain access until the cycle ends rather than receiving a prorated
          refund.
        </p>
        <p>
          <strong className="text-gray-300">App Store / Google Play subscriptions:</strong> refunds for
          purchases made through the App Store or Google Play are handled directly by Apple or Google under
          their own refund policies — Fynora cannot issue these refunds itself. Request one through your Apple
          ID purchase history or Google Play's order history; contact us if you need help finding it.
        </p>
      </PublicSection>

      <PublicSection title="Billing Errors">
        <p>
          If you're charged in error on the web — a duplicate charge, a charge after you cancelled, or an
          incorrect amount — contact us and we will investigate and correct it, including a refund where the
          error is confirmed on our side. For an App Store or Google Play billing error, the store itself is the
          fastest path to a fix (see Refunds above), but reach out to us too if something looks wrong on your
          Fynora account.
        </p>
      </PublicSection>

      <PublicSection title="Free Plan">
        <p>
          The Free plan is not billed, so there is nothing to cancel or refund on it. Downgrading from a paid
          plan to Free follows the same end-of-cycle timing described above.
        </p>
      </PublicSection>

      <PublicSection title="How to Cancel">
        <p>
          On the web, cancel from the Billing page in your account. On iOS or Android, open Subscription from
          the app's More menu — it links directly to your Apple ID or Play Store subscription settings. You can
          also email{' '}
          <a href={SUPPORT_MAILTO} className="text-primary hover:underline">
            {SUPPORT_EMAIL}
          </a>{' '}
          and we'll point you to the right place.
        </p>
      </PublicSection>

      <PublicSection title="Contact">
        <p>
          Questions about a charge or this policy: see{' '}
          <Link to="/contact" className="text-primary hover:underline">Contact Us</Link>.
        </p>
      </PublicSection>
    </PublicLayout>
  );
}
