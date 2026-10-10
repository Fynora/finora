import { Pressable, StyleSheet, Text } from 'react-native';
import { openWebUrl } from '../lib/webUrl';
import { useTheme } from '../theme';

/**
 * One tappable legal link for the auth screens (LegalFooterLinks, SocialConsentNotice,
 * RegisterScreen's consent line). These used to be inline Text with onPress at 12pt: a target
 * well under the 44pt the platform guidelines ask for. A Pressable with its own padding and hit
 * slop gives each link a 46pt-tall target (18pt line + 8pt padding + 6pt slop each side) without
 * the row itself growing into a stack of 44pt bars.
 *
 * Links out via webUrl + Linking rather than an in-app screen -- mobile has no in-app copies of
 * these pages (see RegisterScreen's own comment on this exact constraint).
 */
export function LegalLink({ label, path }: { label: string; path: string }) {
  const c = useTheme();
  return (
    <Pressable onPress={() => openWebUrl(path)} hitSlop={6} accessibilityRole="link" style={styles.link}>
      <Text style={[styles.label, { color: c.primary }]}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  link: { paddingVertical: 8 },
  label: { fontSize: 13, lineHeight: 18, fontWeight: '600' },
});
