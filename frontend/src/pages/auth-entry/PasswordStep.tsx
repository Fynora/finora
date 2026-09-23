import { useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ShieldCheck, ArrowRight } from 'lucide-react';
import type { ConfirmationResult } from 'firebase/auth';
import { useAuth } from '../../context/AuthContext';
import { PasswordInput } from '../../components/PasswordInput';
import { ReactivateAccountPrompt } from '../../components/ReactivateAccountPrompt';
import { SocialSignInButtons } from '../../components/SocialSignInButtons';
import { AuthDivider } from './AuthDivider';
import { SESSION_ENDED_REASON_KEY } from '../../api/client';
import { AUTH_ACCOUNT_DEACTIVATED } from '../../api/errorCodes';
import { safeStorage } from '../../lib/safeStorage';
import { looksLikeValidIdentifier, EMAIL_PATTERN } from './identifierPatterns';
import {
  sendPhoneVerificationCode,
  confirmPhoneVerificationCode,
  resetPhoneVerification,
  friendlySendError,
} from '../../lib/phoneAuth';

interface PasswordStepProps {
  identifier: string;
  banner: string | null;
  onSuccess: (phoneVerified: boolean) => void;
  onNotYou: () => void;
}

// Same courtesy VerifyPhone.tsx's own RESEND_COOLDOWN_SECONDS is -- purely a client-side guard
// against accidental double-clicks; the backend's own 30s cooldown (email channel) and Firebase's
// own rate limiting (phone channel) are the real limits either way.
const OTP_RESEND_COOLDOWN_SECONDS = 30;
const OTP_RECAPTCHA_CONTAINER_ID = 'password-step-otp-recaptcha';

function friendlyFirebaseVerifyError(err: any): string {
  switch (err?.code) {
    case 'auth/invalid-verification-code':
      return "That code doesn't match — check and try again.";
    case 'auth/code-expired':
      return 'This code has expired. Request a new one.';
    default:
      return 'Could not verify — try again.';
  }
}

export function PasswordStep({ identifier: initialIdentifier, banner, onSuccess, onNotYou }: PasswordStepProps) {
  const { login, loginWithGoogle, loginWithApple, loginWithEmailOtpRequest, loginWithEmailOtpVerify, loginWithPhoneOtp } = useAuth();
  // Editable, seeded from the orchestrator's identifier -- same UX as today's Login.tsx, which
  // lets the user correct a mistyped identifier without going all the way back to IDENTIFY.
  const [identifier, setIdentifier] = useState(initialIdentifier);
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [reactivationToken, setReactivationToken] = useState<string | null>(null);
  // Seeded at 400 -- Google's own documented max button width, and its real rendered width as
  // currently measured live on production (see SocialSignInButtons.tsx/GoogleSignInButton.tsx)
  // -- rather than the form's natural full width, so the form narrows to match Google/Apple from
  // the first paint instead of flashing full-width and then snapping narrower once Google's
  // script actually reports back. onWidthKnown corrects this if Google ever renders differently
  // (as it already has once: GIS's button element itself changed shape, from an <iframe> to a
  // plain <div>, which is what made this seed stale at 420 until GoogleSignInButton.tsx's own
  // fix for that).
  const [formWidth, setFormWidth] = useState(400);
  // Same one-shot-read-then-clear pattern as today's Login.tsx -- api/client.ts's forced-signout
  // stashes why the session ended because its window.location.href navigation unmounts React.
  const [sessionEndedReason] = useState<string | null>(() => {
    const reason = safeStorage.getItem(SESSION_ENDED_REASON_KEY);
    if (reason) safeStorage.removeItem(SESSION_ENDED_REASON_KEY);
    return reason;
  });

  // "Login with OTP instead" swaps the password field for a code flow, reusing whatever
  // identifier is already typed above rather than asking for it again.
  const [mode, setMode] = useState<'password' | 'otp'>('password');
  const [otpStage, setOtpStage] = useState<'request' | 'verify'>('request');
  const [otpCode, setOtpCode] = useState('');
  const [otpError, setOtpError] = useState<string | null>(null);
  const [otpSending, setOtpSending] = useState(false);
  const [otpVerifying, setOtpVerifying] = useState(false);
  const [otpResendCooldown, setOtpResendCooldown] = useState(0);
  // Set once the phone channel's sendPhoneVerificationCode() succeeds -- its presence is what
  // distinguishes the phone branch from the email branch throughout the rest of this component,
  // the same way VerifyPhone.tsx's own `confirmation` state does.
  const [phoneConfirmation, setPhoneConfirmation] = useState<ConfirmationResult | null>(null);

  const identifierValid = looksLikeValidIdentifier(identifier);
  const isEmailIdentifier = EMAIL_PATTERN.test(identifier.trim());

  // Ticks the resend cooldown down to 0 once a second, same mechanism VerifyPhone.tsx's own
  // resendCooldown effect uses.
  useEffect(() => {
    if (otpResendCooldown <= 0) return;
    const id = setInterval(() => setOtpResendCooldown((s) => Math.max(0, s - 1)), 1000);
    return () => clearInterval(id);
  }, [otpResendCooldown]);

  function handleAuthError(err: any, fallbackMessage: string) {
    const token = err.response?.data?.errorCode === AUTH_ACCOUNT_DEACTIVATED
      ? err.response?.data?.details?.reactivationToken
      : null;
    if (token) {
      setReactivationToken(token);
    } else {
      setError(err.response?.data?.message ?? fallbackMessage);
    }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!identifier.trim()) { setError('Enter your email or mobile number.'); return; }
    if (!identifierValid) { setError('Enter a valid email address or 10-digit mobile number.'); return; }
    if (password.length === 0) { setError('Enter your password.'); return; }
    setLoading(true);
    try {
      onSuccess(await login(identifier.trim(), password));
    } catch (err: any) {
      handleAuthError(err, 'Login failed. Check your credentials.');
    } finally {
      setLoading(false);
    }
  }

  async function handleGoogleCredential(idToken: string) {
    setError(null);
    setLoading(true);
    try {
      onSuccess(await loginWithGoogle(idToken));
    } catch (err: any) {
      handleAuthError(err, 'Google sign-in failed.');
    } finally {
      setLoading(false);
    }
  }

  async function handleAppleCredential(idToken: string, fullName: string | null) {
    setError(null);
    setLoading(true);
    try {
      onSuccess(await loginWithApple(idToken, fullName));
    } catch (err: any) {
      handleAuthError(err, 'Apple sign-in failed.');
    } finally {
      setLoading(false);
    }
  }

  function enterOtpMode() {
    setError(null);
    setOtpError(null);
    setOtpCode('');
    setOtpStage('request');
    setPhoneConfirmation(null);
    setMode('otp');
  }

  function backToPassword() {
    resetPhoneVerification();
    setOtpError(null);
    setMode('password');
  }

  async function handleRequestOtp(isResend = false) {
    if (!identifier.trim()) { setOtpError('Enter your email or mobile number.'); return; }
    if (!identifierValid) { setOtpError('Enter a valid email address or 10-digit mobile number.'); return; }
    setOtpError(null);
    setOtpSending(true);
    try {
      if (isEmailIdentifier) {
        await loginWithEmailOtpRequest(identifier.trim());
      } else {
        // Firebase requires E.164 -- a bare 10-digit number (identifierPatterns' own
        // PHONE_LIKE_PATTERN accepts either shape) needs the +91 prefix added first.
        const trimmed = identifier.trim();
        const e164 = trimmed.startsWith('+') ? trimmed : `+91${trimmed}`;
        const confirmation = await sendPhoneVerificationCode(e164, OTP_RECAPTCHA_CONTAINER_ID);
        setPhoneConfirmation(confirmation);
      }
      setOtpStage('verify');
      setOtpResendCooldown(OTP_RESEND_COOLDOWN_SECONDS);
    } catch (err: any) {
      if (isEmailIdentifier) {
        setOtpError(err.response?.data?.message ?? 'Could not send a code right now. Please try again.');
      } else {
        resetPhoneVerification();
        setOtpError(friendlySendError(err));
        if (isResend) setOtpResendCooldown(OTP_RESEND_COOLDOWN_SECONDS);
      }
    } finally {
      setOtpSending(false);
    }
  }

  async function handleVerifyOtp(e: FormEvent) {
    e.preventDefault();
    setOtpError(null);
    setOtpVerifying(true);
    try {
      if (phoneConfirmation) {
        const idToken = await confirmPhoneVerificationCode(phoneConfirmation, otpCode);
        onSuccess(await loginWithPhoneOtp(idToken));
      } else {
        onSuccess(await loginWithEmailOtpVerify(identifier.trim(), otpCode));
      }
    } catch (err: any) {
      // A correct code still hits enforceAccountIsSignable -- a deactivated account needs the
      // same reactivation escape hatch handleAuthError already gives the password path, not a
      // dead-end "invalid or expired" message with no way forward.
      if (err.response?.data?.errorCode === AUTH_ACCOUNT_DEACTIVATED) {
        handleAuthError(err, 'Login failed. Check your credentials.');
      } else if (phoneConfirmation) {
        // A wrong/expired SMS code is rejected by Firebase on-device, so there is no backend
        // response to read a message from -- map its error code, same wording VerifyPhone.tsx uses.
        setOtpError(err.response?.data?.message ?? friendlyFirebaseVerifyError(err));
      } else {
        setOtpError(err.response?.data?.message ?? 'That code is invalid or has expired.');
      }
    } finally {
      setOtpVerifying(false);
    }
  }

  if (reactivationToken) {
    return (
      <ReactivateAccountPrompt
        token={reactivationToken}
        onCancel={() => setReactivationToken(null)}
        onReactivated={(phoneVerified) => onSuccess(phoneVerified)}
      />
    );
  }

  return (
    <form onSubmit={mode === 'password' ? handleSubmit : handleVerifyOtp} noValidate style={{ maxWidth: formWidth, marginInline: 'auto' }}>
      <h2 className="font-display text-2xl font-bold text-ink mb-1">Sign in</h2>
      <p className="text-sm text-muted mb-6">Enter your details to access your account</p>

      {banner && (
        <p className="text-success text-sm bg-success-bg rounded-lg px-3 py-2 mb-4">{banner}</p>
      )}
      {sessionEndedReason && !error && (
        <p role="status" className="text-warning text-sm bg-warning-bg rounded-lg px-3 py-2 mb-4">{sessionEndedReason}</p>
      )}
      {error && <p className="text-danger text-sm mb-4">{error}</p>}

      <SocialSignInButtons
        googleText="signin_with"
        onGoogleCredential={handleGoogleCredential}
        onAppleCredential={handleAppleCredential}
        onError={setError}
        onWidthKnown={setFormWidth}
      />

      <AuthDivider />

      <label htmlFor="password-step-identifier" className="block text-xs font-medium text-muted mb-1">Email or mobile number</label>
      <input
        id="password-step-identifier"
        type="text"
        required
        autoComplete="username"
        value={identifier}
        onChange={(e) => setIdentifier(e.target.value)}
        placeholder="you@example.com or +91XXXXXXXXXX"
        className="w-full border border-border rounded-lg px-3 py-2.5 mb-4 text-sm bg-card text-ink focus:outline-none focus:ring-2 focus:ring-primary/30"
      />

      {mode === 'password' ? (
        <>
          <label htmlFor="password-step-password" className="block text-xs font-medium text-muted mb-1">Password</label>
          <PasswordInput
            id="password-step-password"
            value={password}
            onChange={setPassword}
            required
            autoComplete="current-password"
            className="w-full border border-border rounded-lg px-3 py-2.5 pr-10 mb-2 text-sm bg-card text-ink focus:outline-none focus:ring-2 focus:ring-primary/30"
          />
          <p className="text-right mb-2">
            <Link to="/forgot-password" className="text-xs text-primary font-medium">Forgot password?</Link>
          </p>
          <p className="text-right mb-6">
            <button type="button" onClick={enterOtpMode} disabled={loading} className="text-xs text-primary font-medium disabled:opacity-50">
              Login with OTP instead
            </button>
          </p>

          <button
            type="submit"
            disabled={loading}
            className="w-full bg-primary hover:bg-primary-dark active:scale-[0.98] transition-transform text-on-primary rounded-lg py-2.5 text-sm font-semibold flex items-center justify-center gap-1.5 disabled:opacity-50"
          >
            {loading ? 'Signing in…' : 'Sign in'}
            {!loading && <ArrowRight size={15} />}
          </button>
        </>
      ) : otpStage === 'request' ? (
        <>
          {otpError && <p className="text-danger text-sm mb-4">{otpError}</p>}
          <p className="text-sm text-muted mb-4">
            We'll send a one-time code to {isEmailIdentifier ? 'this email address' : 'this mobile number'}.
          </p>
          <button
            type="button"
            onClick={() => handleRequestOtp(false)}
            disabled={otpSending}
            className="w-full bg-primary hover:bg-primary-dark active:scale-[0.98] transition-transform text-on-primary rounded-lg py-2.5 text-sm font-semibold disabled:opacity-50"
          >
            {otpSending ? 'Sending…' : 'Send code'}
          </button>
          <p className="text-sm mt-4 text-center">
            <button type="button" onClick={backToPassword} disabled={otpSending} className="text-primary font-medium disabled:opacity-50">
              Back to password
            </button>
          </p>
        </>
      ) : (
        <>
          {otpError && <p className="text-danger text-sm mb-4">{otpError}</p>}
          <label htmlFor="password-step-otp-code" className="block text-xs font-medium text-muted mb-1">Code</label>
          <input
            id="password-step-otp-code"
            value={otpCode}
            onChange={(e) => setOtpCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
            required
            inputMode="numeric"
            placeholder="123456"
            className="w-full border border-border rounded-lg px-3 py-2.5 mb-4 text-center text-lg tracking-[0.5em] font-mono bg-card text-ink focus:outline-none focus:ring-2 focus:ring-primary/30"
          />
          <button
            type="submit"
            disabled={otpVerifying || otpCode.length !== 6}
            className="w-full bg-primary hover:bg-primary-dark active:scale-[0.98] transition-transform text-on-primary rounded-lg py-2.5 text-sm font-semibold disabled:opacity-50"
          >
            {otpVerifying ? 'Verifying…' : 'Verify'}
          </button>
          <button
            type="button"
            onClick={() => handleRequestOtp(true)}
            disabled={otpSending || otpResendCooldown > 0}
            className="w-full mt-3 text-xs text-primary font-medium text-center disabled:text-muted disabled:cursor-not-allowed"
          >
            {otpSending ? 'Sending…' : otpResendCooldown > 0 ? `Resend in ${otpResendCooldown}s` : "Didn't get a code? Resend"}
          </button>
          <p className="text-sm mt-2 text-center">
            <button type="button" onClick={backToPassword} disabled={otpSending || otpVerifying} className="text-primary font-medium disabled:opacity-50">
              Back to password
            </button>
          </p>
        </>
      )}

      <div className="flex items-start gap-2.5 bg-primary-light rounded-lg p-3 mt-6">
        <ShieldCheck size={16} className="text-primary flex-shrink-0 mt-0.5" />
        <p className="text-xs text-ink">Your financial data is encrypted and securely protected.</p>
      </div>

      <p className="text-sm mt-4 text-center text-muted">
        <button type="button" onClick={onNotYou} disabled={loading} className="text-primary font-medium disabled:opacity-50">Not you?</button>
      </p>

      {/* Anchor for Firebase's invisible reCAPTCHA (phone channel only) -- must exist in the DOM
          before sendPhoneVerificationCode() runs, same as VerifyPhone.tsx's own anchor. */}
      <div id={OTP_RECAPTCHA_CONTAINER_ID} />
    </form>
  );
}
