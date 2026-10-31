import { StyleSheet, Text, View } from 'react-native';
import { LegalLink } from './LegalLink';
import { spacing, useTheme } from '../theme';

/**
 * Terms/Privacy notice for the Google and Apple buttons on AuthEntryScreen and LoginScreen. Those
 * buttons create a brand-new account when the Google/Apple identity has no Fynora account yet, so
 * they are sign-up paths too. Since V302 continuing is no longer taken as agreement: a new account
 * made this way has no acceptance until the user ticks the box on ConsentScreen, shown right after
 * that first sign-in. This line says so up front. Same wording as web's
 * frontend/src/pages/auth-entry/SocialConsentNotice.tsx.
 *
 * The two links sit on their own row under the sentence rather than inline in it: inline Text
 * links are 12pt-tall targets, and LegalLink gives each a proper one.
 */
export function SocialConsentNotice() {
  const c = useTheme();
  return (
    <View style={styles.wrap}>
      <Text style={[styles.text, { color: c.muted }]}>
        New to Fynora? Continuing with Google or Apple creates your account. Before you start, you will
        be asked to read how Fynora uses your data and agree to its
      </Text>
      <View style={styles.links}>
        <LegalLink label="Terms of Service" path="/terms" />
        <LegalLink label="Privacy Policy" path="/privacy" />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { marginTop: spacing.sm },
  text: { fontSize: 13, lineHeight: 18 },
  links: { flexDirection: 'row', flexWrap: 'wrap', columnGap: spacing.md },
});
