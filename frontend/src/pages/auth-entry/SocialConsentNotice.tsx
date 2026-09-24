import { Link } from 'react-router-dom';

/**
 * Terms/Privacy notice for the Google and Apple buttons on the sign-IN steps (IdentifyStep,
 * PasswordStep). Those buttons create a brand-new account when the Google/Apple identity has no
 * Fynora account yet, so they are sign-up paths too -- but only RegisterStep carried the "By
 * continuing, you agree to..." line, which left a whole class of new accounts created without the
 * user ever being shown the terms the backend now records them as accepting (User.termsAcceptedAt).
 * RegisterStep keeps its own, broader line instead of this one, since it covers the password path too.
 */
export function SocialConsentNotice() {
  return (
    <p className="text-xs text-muted mt-3">
      New to Fynora? Continuing with Google or Apple creates your account, and means you agree to Fynora's{' '}
      <Link to="/terms" target="_blank" rel="noopener noreferrer" className="text-primary font-medium">Terms of Service</Link> and{' '}
      <Link to="/privacy" target="_blank" rel="noopener noreferrer" className="text-primary font-medium">Privacy Policy</Link>.
    </p>
  );
}
