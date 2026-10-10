import { StyleSheet, View } from 'react-native';
import { LegalLink } from './LegalLink';
import { spacing } from '../theme';

/**
 * A small legal-link row for auth screens that otherwise have no route to any of these pages --
 * AuthEntryScreen and LoginScreen, specifically. RegisterScreen already links Privacy/Terms from
 * its own consent text ("By continuing, you agree to...", tied to the Create Account action) and
 * doesn't need this too. Mirrors the fix web's AuthEntry.tsx got for the identical gap.
 *
 * Carries the same four links, in the same order, as SettingsScreen's own Legal section (#1513)
 * -- kept in sync deliberately, so a link added to one doesn't quietly go missing from the other.
 * `hideConsentLinks` is the one sanctioned exception: when SocialConsentNotice is on the same
 * screen it already links Terms and Privacy directly under the buttons that need them, so the
 * footer carries only the other two instead of repeating the pair a few lines apart. When the
 * notice is absent (Google unconfigured on Android), the footer shows all four again.
 */
interface Props {
  hideConsentLinks?: boolean;
}

export function LegalFooterLinks({ hideConsentLinks = false }: Props) {
  return (
    <View style={styles.row}>
      {hideConsentLinks ? null : <LegalLink label="Privacy Policy" path="/privacy" />}
      {hideConsentLinks ? null : <LegalLink label="Terms of Service" path="/terms" />}
      <LegalLink label="Trust & Security" path="/trust" />
      <LegalLink label="Data Portability Promise" path="/your-data" />
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'center',
    columnGap: spacing.md,
    marginTop: spacing.sm,
  },
});
