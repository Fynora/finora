import { StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { spacing, useTheme } from '../theme';

/**
 * The "your data is encrypted" reassurance under the submit button on the three auth screens.
 * It was a filled box with ink text, which read like a third banner next to the error and info
 * banners above the form; a lock glyph and a muted caption say the same thing at the weight of a
 * footnote. One component so the three screens cannot drift apart again.
 */
export function TrustNote() {
  const c = useTheme();
  return (
    <View style={styles.row} accessible accessibilityRole="text">
      <Ionicons name="lock-closed-outline" size={14} color={c.muted} />
      <Text style={[styles.text, { color: c.muted }]}>
        Your financial data is encrypted and securely protected.
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    marginTop: spacing.md,
  },
  text: {
    fontSize: 13,
    lineHeight: 18,
    flexShrink: 1,
  },
});
