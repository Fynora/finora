import { useEffect, useMemo, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { ShieldAlert } from 'lucide-react';
import { authApi } from '../api/endpoints';
import { PasswordInput } from '../components/PasswordInput';
import { sendPhoneVerificationCode, confirmPhoneVerificationCode, resetPhoneVerification } from '../lib/phoneAuth';
import { reportHandledError } from '../lib/monitoring';
import type { ConfirmationResult } from 'firebase/auth';

const RECAPTCHA_CONTAINER_ID = 'reset-password-recaptcha';

// Same heuristic as frontend/'s Register.tsx/ResetPassword.tsx -- kept identical so the meter
// means the same thing wherever a password gets set anywhere in Fynora, admin or not.
function passwordStrength(pw: string): { score: number; label: string; color: string } {
  let score = 0;
  if (pw.length >= 8) score++;
  if (/[a-z]/.test(pw) && /[A-Z]/.test(pw)) score++;
  if (/[0-9]/.test(pw)) score++;
  if (/[^A-Za-z0-9]/.test(pw)) score++;
  const labels = ['Too short', 'Weak', 'Fair', 'Good', 'Strong'];
  const colors = ['bg-gray-300', 'bg-danger', 'bg-warning', 'bg-info', 'bg-success'];
  return { score, label: labels[score], color: colors[score] };
}

// Kept word-for-word identical to frontend/'s ResetPassword.tsx -- this was "Could not reset
// password. The code may be wrong or the link may have expired." here, different wording for the
// exact same failure a user could hit in either app. "Could not verify" is also the more precise
// of the two: this error is specifically the phone-verification step failing, not the reset as a
// whole.
function friendlyFirebaseError(err: any): string {
  switch (err?.code) {
    case 'auth/invalid-verification-code':
      return "That code doesn't match — check and try again.";
    case 'auth/code-expired':
      return 'This code has expired. Request a new one.';
    default:
      return 'Could not verify. The code may be wrong, or the link may have expired.';
  }
}

// Same rule as frontend/'s ResetPassword.tsx. Every way an account gets a phone number (sign-up,
// first-time setup, an admin creating or editing one) accepts only a 10-digit Indian mobile
// number, so a fixed +91 box can reach every admin's number.
const PHONE_PATTERN = /^[6-9][0-9]{9}$/;
function sanitizeLocalPhoneNumber(raw: string): string {
  return raw.replace(/[^0-9]/g, '').slice(0, 10);
}

/**
 * A reset link alone (proof of email access) is not enough to change a password, for an admin
 * account least of all: a phone code via Firebase Phone Authentication is required too, through
 * the same backend endpoints the user-facing app's ResetPassword.tsx uses.
 *
 * The admin types their own number. authApi.verifyResetPasswordPhone confirms it matches the
 * account, never returning the real one (BH-015), and only then is the typed number handed to
 * Firebase, which sends and checks the code; the backend sees only the resulting ID token.
 *
 * Until this was written that way, the page asked the server for the account's number as soon as
 * it opened, sending the token alone. The server had stopped answering that when BH-015 turned
 * the endpoint around, so the request was refused and no admin could get a code or reset a
 * password here.
 */
export default function ResetPassword() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const token = searchParams.get('token');
  const [phoneLocal, setPhoneLocal] = useState('');
  const [phoneConfirmed, setPhoneConfirmed] = useState(false);
  const [verifyingPhone, setVerifyingPhone] = useState(false);
  const [phoneError, setPhoneError] = useState<string | null>(null);
  const phoneValid = PHONE_PATTERN.test(phoneLocal);

  const [otp, setOtp] = useState('');
  const [confirmation, setConfirmation] = useState<ConfirmationResult | null>(null);
  const [otpError, setOtpError] = useState<string | null>(null);
  const [sendingOtp, setSendingOtp] = useState(false);
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [done, setDone] = useState(false);

  // The proof of the phone code, kept once the code has been checked. The server can refuse the
  // new password itself (too common, same as the current one) after the code was accepted, and
  // the next try must send this again instead of asking Firebase to check a code it has already
  // used. Same as frontend/'s ResetPassword.tsx. Cleared when a new code is sent.
  const verifiedIdToken = useRef<string | null>(null);

  async function sendOtp(fullPhoneNumber: string) {
    setSendingOtp(true);
    setOtpError(null);
    setOtp('');
    setConfirmation(null);
    verifiedIdToken.current = null;
    try {
      const result = await sendPhoneVerificationCode(fullPhoneNumber, RECAPTCHA_CONTAINER_ID);
      setConfirmation(result);
    } catch (err: unknown) {
      reportHandledError(err, 'reset-password-send-otp');
      // A used or expired invisible reCAPTCHA throws when it is reused, so the next try needs a
      // fresh one whatever just failed.
      resetPhoneVerification();
      setOtpError('Could not send a verification code right now. Please try again.');
    } finally {
      setSendingOtp(false);
    }
  }

  async function submitPhoneNumber(e: FormEvent) {
    e.preventDefault();
    setTouched((t) => ({ ...t, phone: true }));
    if (!token || !phoneValid) return;
    setPhoneError(null);
    setVerifyingPhone(true);
    const fullPhoneNumber = `+91${phoneLocal}`;
    try {
      await authApi.verifyResetPasswordPhone(token, fullPhoneNumber);
    } catch (err: any) {
      setPhoneError(err.response?.data?.message ?? 'Could not verify that phone number. Please try again.');
      setVerifyingPhone(false);
      return;
    }
    setPhoneConfirmed(true);
    setVerifyingPhone(false);
    await sendOtp(fullPhoneNumber);
  }

  // Leaving the page clears the reCAPTCHA, so a later visit gets one bound to its own page.
  useEffect(() => () => resetPhoneVerification(), []);

  const passwordLongEnough = password.length >= 8;
  const passwordsMatch = confirm.length > 0 && confirm === password;
  const otpValid = /^\d{6}$/.test(otp);
  const strength = useMemo(() => passwordStrength(password), [password]);
  const formValid = Boolean(token) && Boolean(confirmation) && otpValid && passwordLongEnough && passwordsMatch;

  function markTouched(field: string) {
    setTouched((t) => ({ ...t, [field]: true }));
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setTouched((t) => ({ ...t, password: true, confirm: true, otp: true }));
    if (!token) { setError('No reset token found in the link.'); return; }
    if (!confirmation) { setError('Enter the 6-digit verification code sent to your phone.'); return; }
    if (!otpValid) { setError('Enter the 6-digit verification code sent to your phone.'); return; }
    if (!passwordLongEnough) { setError('Password must be at least 8 characters.'); return; }
    if (!passwordsMatch) { setError('Passwords do not match.'); return; }
    setError(null);
    setLoading(true);
    try {
      const firebaseIdToken = verifiedIdToken.current ?? await confirmPhoneVerificationCode(confirmation, otp);
      verifiedIdToken.current = firebaseIdToken;
      await authApi.resetPassword(token, firebaseIdToken, password);
      setDone(true);
      // Was a bare navigate('/login') with no message at all -- unlike the user app's identical
      // flow (frontend/src/pages/ResetPassword.tsx), which already hands this exact text through
      // router state. An admin who just reset their password landed on the login screen with zero
      // acknowledgment it worked. Text kept identical across both apps for the same reason
      // friendlyFirebaseError() above was just aligned.
      setTimeout(() => {
        void navigate('/login', {
          state: { message: 'Password reset successfully. Please sign in using your new password.' },
        });
      }, 2000);
    } catch (err: any) {
      setError(err.response?.data?.message ?? friendlyFirebaseError(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen bg-bg flex items-center justify-center p-4">
      <div className="w-full max-w-sm">
        <div className="flex items-center gap-2.5 justify-center mb-8">
          {/* to-[#15171C] rather than to-primary-dark: primary-dark flips to light paper in dark
              mode, which would fade this badge's rose-to-dark gradient into a rose-to-pale one --
              pinning the dark end keeps the white icon readable in both themes. */}
          <div className="w-9 h-9 rounded-lg bg-gradient-to-br from-rose-400 to-[#15171C] flex items-center justify-center">
            <ShieldAlert size={18} className="text-white" strokeWidth={2.5} />
          </div>
          <span className="font-extrabold tracking-wide text-xl text-ink">FYNORA ADMIN</span>
        </div>

        <div className="bg-card border border-border rounded-xl2 shadow-soft p-6">
          {/* Anchor for Firebase's invisible reCAPTCHA. Rendered once, outside the steps below, and
              never unmounted while this page is up: the verifier is built against this exact node
              the first time a code is sent, and a copy inside each step would be swapped out from
              under it when the page moves from the phone step to the code step. */}
          <div id={RECAPTCHA_CONTAINER_ID} />

          {done ? (
            <>
              <h1 className="text-lg font-bold text-ink mb-2">Password updated</h1>
              <p className="text-sm text-muted">Redirecting you to sign in…</p>
            </>
          ) : !token ? (
            <>
              <p className="text-xs text-danger mb-4">
                No reset token found in the link — this page normally opens from the email link.
              </p>
              <p className="text-sm text-center">
                <Link to="/login" className="text-primary font-medium">Back to sign in</Link>
              </p>
            </>
          ) : !phoneConfirmed ? (
            <form onSubmit={submitPhoneNumber} noValidate>
              <h1 className="text-lg font-bold text-ink mb-2">Confirm your phone number</h1>
              <p className="text-xs text-muted mb-4">
                Enter the mobile number on this account. We'll use it to send a verification code.
              </p>
              {phoneError && <p className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5 mb-4">{phoneError}</p>}

              <label htmlFor="reset-phone" className="block text-sm font-medium text-ink mb-1.5">Mobile number</label>
              <div className="relative mb-1">
                <div className="absolute left-3.5 top-1/2 -translate-y-1/2 flex items-center gap-1.5 text-sm text-ink pointer-events-none select-none">
                  <span>+91</span>
                  <span className="w-px h-4 bg-border" />
                </div>
                <input
                  id="reset-phone"
                  type="tel"
                  inputMode="numeric"
                  autoComplete="tel-national"
                  value={phoneLocal}
                  onChange={(e) => { setPhoneLocal(sanitizeLocalPhoneNumber(e.target.value)); setPhoneError(null); }}
                  onBlur={() => markTouched('phone')}
                  placeholder="XXXXXXXXXX"
                  maxLength={10}
                  className="w-full bg-bg border border-border rounded-lg pl-16 pr-3.5 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30"
                />
              </div>
              <p className="text-[11px] mb-4 h-3.5">
                {touched.phone && !phoneValid && <span className="text-danger">Enter a valid 10-digit mobile number.</span>}
              </p>

              <button
                type="submit"
                disabled={verifyingPhone || !phoneValid}
                className="w-full bg-primary hover:bg-primary-dark text-on-primary font-semibold rounded-lg py-2.5 text-sm disabled:opacity-50"
              >
                {verifyingPhone ? 'Confirming…' : 'Continue'}
              </button>

              <p className="text-sm mt-4 text-center">
                <Link to="/login" className="text-primary font-medium">Back to sign in</Link>
              </p>
            </form>
          ) : (
            <form onSubmit={handleSubmit} noValidate>
              <h1 className="text-lg font-bold text-ink mb-2">Set a new password</h1>
              {error && <p className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5 mb-4">{error}</p>}

              <div className="mb-4">
                <label htmlFor="reset-otp" className="block text-sm font-medium text-ink mb-1">Verification code</label>
                <p className="text-xs text-muted mb-2">
                  {confirmation
                    ? `Enter the 6-digit code sent to +91${phoneLocal}.`
                    : 'Sending a verification code…'}
                </p>
                {otpError && <p className="text-danger text-xs mb-2">{otpError}</p>}
                <input
                  id="reset-otp"
                  value={otp}
                  onChange={(e) => setOtp(e.target.value.replace(/\D/g, '').slice(0, 6))}
                  onBlur={() => markTouched('otp')}
                  inputMode="numeric"
                  placeholder="123456"
                  disabled={!confirmation}
                  className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 text-center text-lg tracking-[0.4em] font-mono focus:outline-none focus:ring-2 focus:ring-primary/30 mb-1 disabled:opacity-50"
                />
                <div className="flex items-center justify-between">
                  <p className="text-[11px] h-3.5">
                    {touched.otp && !otpValid && <span className="text-danger">Enter the 6-digit code.</span>}
                  </p>
                  <button
                    type="button"
                    onClick={() => void sendOtp(`+91${phoneLocal}`)}
                    disabled={sendingOtp || loading}
                    className="text-[11px] text-primary font-medium disabled:opacity-50"
                  >
                    {sendingOtp ? 'Sending…' : 'Resend code'}
                  </button>
                </div>
              </div>

              <label htmlFor="reset-new-password" className="block text-sm font-medium text-ink mb-1.5">New password</label>
              <PasswordInput
                id="reset-new-password"
                required
                minLength={8}
                maxLength={72}
                autoComplete="new-password"
                value={password}
                onChange={setPassword}
                onBlur={() => markTouched('password')}
                className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 pr-10 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30"
              />
              {password.length > 0 && (
                <div className="mt-2 mb-1">
                  <div className="flex gap-1 mb-1">
                    {[0, 1, 2, 3].map((i) => (
                      <div key={i} className={`h-1 flex-1 rounded-full ${i < strength.score ? strength.color : 'bg-gray-200'}`} />
                    ))}
                  </div>
                  <p className="text-[11px] text-muted">{strength.label}</p>
                </div>
              )}
              <p className="text-[11px] mb-3 h-3.5">
                {touched.password && !passwordLongEnough && password.length > 0 && (
                  <span className="text-danger">Password must be at least 8 characters.</span>
                )}
              </p>

              <label htmlFor="reset-confirm-password" className="block text-sm font-medium text-ink mb-1.5">Confirm password</label>
              <PasswordInput
                id="reset-confirm-password"
                required
                autoComplete="new-password"
                value={confirm}
                onChange={setConfirm}
                onBlur={() => markTouched('confirm')}
                className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 pr-10 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30 mb-1"
              />
              <p className="text-[11px] mb-4 h-3.5">
                {touched.confirm && !passwordsMatch && <span className="text-danger">Passwords don't match.</span>}
              </p>

              <button
                type="submit"
                disabled={loading || !formValid}
                className="w-full bg-primary hover:bg-primary-dark text-on-primary font-semibold rounded-lg py-2.5 text-sm disabled:opacity-50"
              >
                {loading ? 'Updating…' : 'Update password'}
              </button>

              <p className="text-sm mt-4 text-center">
                <Link to="/login" className="text-primary font-medium">Back to sign in</Link>
              </p>
            </form>
          )}
        </div>
      </div>
    </div>
  );
}
