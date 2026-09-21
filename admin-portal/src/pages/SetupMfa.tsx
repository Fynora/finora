import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { ShieldCheck } from 'lucide-react';
import { useAdminAuth } from '../context/AdminAuthContext';
import { EnrollFlow } from '../components/MfaSection';

/**
 * The forced "set up two-factor authentication" screen (CASA 3.3.1: MFA is mandatory for every
 * admin account). Reached from ProtectedRoute, or from the API client, whenever the backend has
 * answered MFA_ENROLLMENT_REQUIRED -- AdminMfaEnrollmentFilter refuses every request from an
 * admin who has not enrolled except the enrolment endpoints and sign-out, so this is the only way
 * forward, and it is always available.
 *
 * Reuses the exact enrolment flow the Settings page has (scan the QR code, confirm a code, save
 * the recovery codes) rather than a second implementation. The recovery-codes step is the part
 * that matters most here: it is the only time they are ever shown, and they are the way back in
 * if the authenticator is lost.
 *
 * An admin who is already enrolled has nothing to do here and is sent to the dashboard.
 */
export default function SetupMfa() {
  const navigate = useNavigate();
  const { mfaEnrollmentRequired, completeMfaEnrollment, logout } = useAdminAuth();
  const [error, setError] = useState<string | null>(null);

  if (!mfaEnrollmentRequired) return <Navigate to="/" replace />;

  async function handleEnrolled() {
    setError(null);
    try {
      await completeMfaEnrollment();
      void navigate('/', { replace: true });
    } catch (err: any) {
      setError(err?.message ?? 'Could not finish setting up. Please sign in again.');
    }
  }

  return (
    <div className="min-h-screen bg-bg flex items-center justify-center p-4">
      <div className="w-full max-w-lg bg-card border border-border rounded-xl2 shadow-card p-6 space-y-4">
        <div className="flex items-center gap-2.5">
          <ShieldCheck size={20} className="text-primary" />
          <h1 className="font-semibold text-ink text-lg">Set up two-factor authentication</h1>
        </div>
        <p className="text-sm text-muted">
          Two-factor authentication is required for every admin account. Set it up now to continue
          to the admin portal.
        </p>
        <EnrollFlow onEnrolled={() => { void handleEnrolled(); }} />
        {error && <p className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5">{error}</p>}
        <div className="pt-2 border-t border-border">
          <button type="button" onClick={logout} className="text-muted hover:text-ink text-xs font-medium">
            Sign out
          </button>
        </div>
      </div>
    </div>
  );
}
