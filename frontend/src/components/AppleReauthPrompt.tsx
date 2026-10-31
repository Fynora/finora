import { AppleSignInButton } from './AppleSignInButton';
import { isAppleLoginConfigured } from '../lib/appleIdentity';

/**
 * The "prove you're still you" step for a Sign in with Apple account -- the Apple counterpart to
 * GoogleReauthPrompt, used wherever a sensitive action would otherwise ask for a current password
 * (ChangePasswordModal, ChangeEmailModal, DeleteAccountModal, DeactivateAccountModal,
 * ExportDataModal). An APPLE-method account's password is a random value nobody ever knows, so
 * Apple's own credential (a fresh identity token, re-verified server-side by
 * GoogleReauthVerifier) proves control instead.
 *
 * Checks isAppleLoginConfigured() explicitly for the same reason GoogleReauthPrompt checks its own
 * flag: AppleSignInButton renders nothing when unconfigured, which would leave an APPLE-method
 * user looking at "verify your identity" with nothing to press and no explanation.
 */
export function AppleReauthPrompt({ onCredential, onError }: {
  onCredential: (idToken: string) => void | Promise<void>;
  onError: (message: string) => void;
}) {
  if (!isAppleLoginConfigured()) {
    return (
      <p className="text-xs text-danger">
        Sign in with Apple isn't available right now. Please try again later, or contact support.
      </p>
    );
  }
  return (
    <div>
      <p className="text-xs text-muted mb-2">
        This account signs in with Apple. Verify your identity with Apple to continue.
      </p>
      {/* The button also hands back a display name; re-auth needs only the token. */}
      <AppleSignInButton onCredential={(idToken) => onCredential(idToken)} onError={onError} />
    </div>
  );
}
