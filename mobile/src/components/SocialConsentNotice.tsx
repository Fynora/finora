import { StyleSheet, Text } from 'react-native';
import { openWebUrl } from '../lib/webUrl';
import { spacing, useTheme } from '../theme';

/**
 * Terms/Privacy notice for the Google and Apple buttons on AuthEntryScreen and LoginScreen. Those
 * buttons create a brand-new account when the Google/Apple identity has no Fynora account yet, so
 * they are sign-up paths too -- but only RegisterScreen carried the "By continuing, you agree
 * to..." line, which left new accounts created without the user ever being shown the terms the
 * backend now records them as accepting (User.termsAcceptedAt). Same wording as web's
 * frontend/src/pages/auth-entry/SocialConsentNotice.tsx.
 */
export function SocialConsentNotice() {
  const c = useTheme();
  return (
    <Text style={[styles.text, { color: c.muted }]}>
      New to Fynora? Continuing with Google or Apple creates your account, and means you agree to
      Fynora&apos;s{' '}
      <Text style={[styles.link, { color: c.primary }]} onPress={() => openWebUrl('/terms')}>
        Terms of Service
      </Text>{' '}
      and{' '}
      <Text style={[styles.link, { color: c.primary }]} onPress={() => openWebUrl('/privacy')}>
        Privacy Policy
      </Text>
      .
    </Text>
  );
}

const styles = StyleSheet.create({
  text: { fontSize: 12, lineHeight: 17, marginTop: spacing.sm },
  link: { fontWeight: '600' },
});
