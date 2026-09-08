import { Platform, StyleSheet, Text, View } from 'react-native';
import { AppleSignInButton } from './AppleSignInButton';
import { spacing, useTheme } from '../theme';

/**
 * Phase 4 (Medium-Tier Parity). Mobile-only counterpart to GoogleReauthPrompt -- web has no Apple
 * Sign-In at all (see AppleSignInButton's own doc comment on why Apple requires it only on iOS,
 * with no web/Android equivalent rule), so there is nothing to port here; this is new.
 *
 * Same reasoning as GoogleReauthPrompt: an APPLE-method account's password is a random value
 * nobody ever knows, so a password field can never be filled in correctly here -- Apple's own
 * credential (a fresh identity token, re-verified server-side) proves control instead.
 *
 * Explicitly checks Platform.OS rather than trusting AppleSignInButton to degrade silently: that
 * button renders nothing at all off iOS, which would leave an APPLE-method user on Android (they
 * originally signed up on an iPhone, but use this account on both) staring at "verify your
 * identity" with no interactive control anywhere underneath -- the exact silent-lockout bug
 * GoogleReauthPrompt's own doc comment already found once, reached from the platform axis instead
 * of the configuration one.
 */
export function AppleReauthPrompt({ onCredential, onError }: {
  onCredential: (idToken: string) => void | Promise<void>;
  onError: (message: string) => void;
}) {
  const c = useTheme();

  if (Platform.OS !== 'ios') {
    return (
      <Text style={[styles.unavailable, { color: c.danger }]}>
        Verifying with Apple needs an iPhone or iPad. Please try again on an Apple device, or
        contact support.
      </Text>
    );
  }

  return (
    <View>
      <Text style={[styles.body, { color: c.muted }]}>
        This account signs in with Apple. Verify your identity with Apple to continue.
      </Text>
      <AppleSignInButton onCredential={onCredential} onError={onError} />
    </View>
  );
}

const styles = StyleSheet.create({
  body: { fontSize: 13, lineHeight: 19, marginBottom: spacing.sm },
  unavailable: { fontSize: 13, lineHeight: 19 },
});
