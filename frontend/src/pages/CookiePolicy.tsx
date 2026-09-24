import { Link } from 'react-router-dom';
import { PublicLayout, PublicSection } from '../components/PublicLayout';

export default function CookiePolicy() {
  return (
    <PublicLayout
      title="Cookie Policy"
      subtitle="Last updated: September 2026. What Fynora stores in your browser, and why — see also our Privacy Policy for how we handle your data more broadly."
    >
      <PublicSection title="Overview">
        <p>
          Fynora uses one cookie and a small amount of browser local storage, both strictly to make
          the web app work — signing you in, remembering your theme, and similar. We do not use
          advertising cookies, tracking cookies, or any third-party analytics cookies, so there is
          nothing non-essential here that would require a cookie-consent banner.
        </p>
      </PublicSection>

      <PublicSection title="The One Cookie We Set">
        <p>
          Fynora sets a single cookie: a session refresh token, used to keep you signed in without
          asking for your password again on every visit. It is <code>HttpOnly</code> (invisible to
          any JavaScript, including Fynora's own), <code>Secure</code> (sent only over HTTPS), and
          <code> SameSite=Lax</code>, and it's scoped only to our authentication endpoints rather
          than the whole site. It's deleted when you sign out, and expires automatically after a
          period of inactivity — see the "Data Encryption & Security" section of our{' '}
          <Link to="/privacy" className="text-primary hover:underline">Privacy Policy</Link> for the
          exact session limits. Because this cookie is what keeps you signed in, blocking it in your
          browser will prevent Fynora from working — there's no separate "non-essential" cookie to
          opt out of instead.
        </p>
      </PublicSection>

      <PublicSection title="Browser Local Storage">
        <p>
          Separately from that cookie, Fynora keeps a few small values in your browser's local
          storage — a mechanism similar to a cookie, but never sent to our servers automatically.
          None of it is ever shared with any third party, and all of it can be cleared at once from
          your browser's site-data settings for fynora.net.
        </p>
        <p>
          Some of it identifies your account and is cleared when you sign out: your email address,
          display name, a couple of account-status flags (so the app doesn't flash a "loading" state
          you've already resolved, or ask you to re-verify something you've already done), and a
          one-time message explaining why you were signed out, shown once on your next visit and
          then deleted.
        </p>
        <p>
          The rest is a device-level preference that deliberately survives sign-out, so it doesn't
          reset every time you sign back in: your light/dark theme; whether the sidebar is collapsed;
          which notification messages you've marked as read (kept per account, so signing a
          different person into the same browser doesn't inherit your read history); and, on the
          pricing page, which billing cycle you were about to choose.
        </p>
      </PublicSection>

      <PublicSection title="No Advertising or Tracking Cookies">
        <p>
          Fynora does not run advertising pixels, and does not use Google Analytics, Meta Pixel, or
          any similar tracking or analytics cookie. We use Sentry for crash reporting, but only to
          capture errors when they happen — session tracing and screen replay are both switched off
          in our configuration, so Sentry does not set a cookie or track you across visits.
        </p>
        <p>
          A few things on the site load from other companies' servers rather than ours: the website's
          typefaces (Google Fonts) on every page; the "Sign in with Google" and "Sign in with Apple"
          scripts on the sign-in and sign-up screens, and Google's again when a Google account
          re-confirms its identity (for example, before deleting the account); and Razorpay's checkout
          script, only when you start a payment. Loading them sends that company your IP address and
          browser details, and it may apply its own cookies to those requests under its own privacy
          policy. Fynora does not set, read, or receive any of those cookies. To make the Google button
          appear faster, every page also opens a connection to Google's sign-in server in advance; that
          connection reveals your IP address to Google but sends no request and no cookies until the
          button itself loads.
        </p>
      </PublicSection>

      <PublicSection title="Managing Cookies">
        <p>
          Because Fynora only sets the one essential authentication cookie described above, there's
          no cookie preference center — turning cookies off entirely in your browser settings will
          sign you out and prevent you from signing back in, since that's the cookie's only job.
          Local storage can be cleared independently from your browser's site-data settings without
          affecting your account, though you'll lose the small conveniences described above (theme,
          sidebar state, and so on) until they're set again.
        </p>
      </PublicSection>

      <PublicSection title="Questions">
        <p>
          This page covers cookies and local storage specifically. For how Fynora handles your
          financial and personal data more broadly, see our{' '}
          <Link to="/privacy" className="text-primary hover:underline">Privacy Policy</Link>.
        </p>
      </PublicSection>
    </PublicLayout>
  );
}
