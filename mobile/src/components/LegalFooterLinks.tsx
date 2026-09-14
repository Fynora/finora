import { Linking, StyleSheet, Text } from 'react-native';
import { webUrl } from '../lib/webUrl';
import { spacing, useTheme } from '../theme';

/**
 * A small "Privacy Policy · Terms of Service" link row for auth screens that otherwise have no
 * route to either page -- AuthEntryScreen and LoginScreen, specifically. RegisterScreen already
 * links the same two pages from its own consent text ("By continuing, you agree to...", tied to
 * the Create Account action) and doesn't need this too. Mirrors the fix web's AuthEntry.tsx got
 * for the identical gap.
 *
 * Only these two, not a third "Trust & Security" link the way web's footer also carries -- mobile
 * has never surfaced that page anywhere (SettingsScreen's own Legal section, the other place these
 * two links live, doesn't either), so adding one here would be introducing a new reference rather
 * than closing the actual gap.
 *
 * Links out via webUrl + Linking rather than an in-app screen -- mobile has no in-app copies of
 * Terms/Privacy (see RegisterScreen's own comment on this exact constraint).
 */
export function LegalFooterLinks() {
  const c = useTheme();
  return (
    <Text style={[styles.text, { color: c.muted }]}>
      <Text style={[styles.link, { color: c.primary }]} onPress={() => Linking.openURL(webUrl('/privacy'))}>
        Privacy Policy
      </Text>
      {'  ·  '}
      <Text style={[styles.link, { color: c.primary }]} onPress={() => Linking.openURL(webUrl('/terms'))}>
        Terms of Service
      </Text>
    </Text>
  );
}

const styles = StyleSheet.create({
  text: { fontSize: 12, textAlign: 'center', marginTop: spacing.sm },
  link: { fontWeight: '600' },
});
