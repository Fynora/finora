import { Link } from 'react-router-dom';

/**
 * Terms/Privacy notice for the Google and Apple buttons on the sign-IN steps (IdentifyStep,
 * PasswordStep). Those buttons create a brand-new account when the Google/Apple identity has no
 * Fynora account yet, so they are sign-up paths too. Since V302 continuing is no longer taken as
 * agreement: a new account made this way has no acceptance until the user ticks the box on the
 * consent step (ConsentGate), shown right after that first sign-in. This line says so up front.
 */
export function SocialConsentNotice() {
  return (
    <p className="text-xs text-muted mt-3">
      New to Fynora? Continuing with Google or Apple creates your account. Before you start, you will be
      asked to read how Fynora uses your data and agree to its{' '}
      <Link to="/terms" target="_blank" rel="noopener noreferrer" className="text-primary font-medium">Terms of Service</Link> and{' '}
      <Link to="/privacy" target="_blank" rel="noopener noreferrer" className="text-primary font-medium">Privacy Policy</Link>.
    </p>
  );
}
