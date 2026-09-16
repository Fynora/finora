import { StyleSheet, Text } from 'react-native';
import { openWebUrl } from '../lib/webUrl';
import { spacing, useTheme } from '../theme';

/**
 * A small legal-link row for auth screens that otherwise have no route to any of these pages --
 * AuthEntryScreen and LoginScreen, specifically. RegisterScreen already links Privacy/Terms from
 * its own consent text ("By continuing, you agree to...", tied to the Create Account action) and
 * doesn't need this too. Mirrors the fix web's AuthEntry.tsx got for the identical gap.
 *
 * Carries the same four links, in the same order, as SettingsScreen's own Legal section (#1513)
 * -- kept in sync deliberately, so a link added to one doesn't quietly go missing from the other.
 *
 * Links out via webUrl + Linking rather than an in-app screen -- mobile has no in-app copies of
 * these pages (see RegisterScreen's own comment on this exact constraint).
 */
export function LegalFooterLinks() {
  const c = useTheme();
  const link = (label: string, path: string) => (
    <Text style={[styles.link, { color: c.primary }]} onPress={() => openWebUrl(path)}>
      {label}
    </Text>
  );
  return (
    <Text style={[styles.text, { color: c.muted }]}>
      {link('Privacy Policy', '/privacy')}
      {'  ·  '}
      {link('Terms of Service', '/terms')}
      {'  ·  '}
      {link('Trust & Security', '/trust')}
      {'  ·  '}
      {link('Data Portability Promise', '/your-data')}
    </Text>
  );
}

const styles = StyleSheet.create({
  text: { fontSize: 12, textAlign: 'center', marginTop: spacing.sm },
  link: { fontWeight: '600' },
});
