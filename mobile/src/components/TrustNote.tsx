import { StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { spacing, useTheme } from '../theme';

/**
 * The encryption reassurance under the submit button on the three auth screens. It says only what
 * Fynora itself encrypts -- statement files (StatementContentService, and staged uploads since V304)
 * -- not "your financial data": transactions rely on the hosting provider (Privacy Policy, Data
 * Encryption & Security).
 * It was a filled box with ink text, which read like a third banner next to the error and info
 * banners above the form; a lock glyph and a muted caption say the same thing at the weight of a
 * footnote. One component so the three screens cannot drift apart again.
 */
const COPY = 'Statements you upload are stored encrypted. Your data is never sold.';

export function TrustNote() {
  const c = useTheme();
  return (
    // One accessibility element with the sentence as its label: without the label the glyph
    // joins in as an empty item and the simulator's tree read ", Your financial data…".
    <View style={styles.row} accessible accessibilityRole="text" accessibilityLabel={COPY}>
      <Ionicons name="lock-closed-outline" size={14} color={c.muted} />
      <Text style={[styles.text, { color: c.muted }]}>{COPY}</Text>
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
