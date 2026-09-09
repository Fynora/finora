import { StyleSheet, Text, View } from 'react-native';
import { GoogleSignInButton, isGoogleSignInConfigured } from './GoogleSignInButton';
import { spacing, useTheme } from '../theme';

/**
 * Phase 4 (Medium-Tier Parity). Ported from frontend/src/components/GoogleReauthPrompt.tsx -- the
 * "prove you're still you" step for a GOOGLE-method account, wherever a sensitive action would
 * otherwise ask for a current password (ChangePasswordSheet, ChangeEmailSheet, and -- once each
 * is wired the same way -- DeactivateAccountSheet/DeleteAccountSheet/ExportDataSheet, which
 * currently show an honest "not available yet" message for exactly this case). A GOOGLE-method
 * account's password is a random value nobody, including the user, ever knows (see the backend
 * User.signInMethod's own doc comment), so a password field can never be filled in correctly here;
 * this renders Google's own button instead, and its credential (a fresh ID token, re-verified
 * server-side by GoogleReauthVerifier) is what proves control in place of a password.
 *
 * Checks isGoogleSignInConfigured() explicitly rather than trusting GoogleSignInButton to degrade
 * silently: that button renders nothing at all when unconfigured, which would leave a GOOGLE-
 * method user staring at "verify your identity" with no interactive control anywhere underneath
 * and no explanation -- the same bug web's own doc comment on this component already found once.
 */
export function GoogleReauthPrompt({ onCredential, onError }: {
  onCredential: (idToken: string) => void | Promise<void>;
  onError: (message: string) => void;
}) {
  const c = useTheme();

  if (!isGoogleSignInConfigured()) {
    return (
      <Text style={[styles.unavailable, { color: c.danger }]}>
        Sign in with Google isn&apos;t available right now. Please try again later, or contact
        support.
      </Text>
    );
  }

  return (
    <View>
      <Text style={[styles.body, { color: c.muted }]}>
        This account signs in with Google. Verify your identity with Google to continue.
      </Text>
      <GoogleSignInButton onCredential={onCredential} onError={onError} />
    </View>
  );
}

const styles = StyleSheet.create({
  body: { fontSize: 13, lineHeight: 19, marginBottom: spacing.sm },
  unavailable: { fontSize: 13, lineHeight: 19 },
});
