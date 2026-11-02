import { useState, type ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { spacing, useTheme } from '../../theme';

/**
 * A line that opens to show more, closed by default. Same look as StatementHandlingPanel's own
 * toggle, which sits beside it on the Import screen.
 */
export function FoldLine({ label, children, testID }: { label: string; children: ReactNode; testID?: string }) {
  const c = useTheme();
  const [expanded, setExpanded] = useState(false);
  return (
    <View style={styles.wrap} testID={testID}>
      <Pressable
        onPress={() => setExpanded((open) => !open)}
        accessibilityRole="button"
        accessibilityLabel={label}
        accessibilityState={{ expanded }}
        style={styles.header}
      >
        <Text style={[styles.title, { color: c.ink }]}>{label}</Text>
        <Ionicons name={expanded ? 'chevron-up' : 'chevron-down'} size={15} color={c.muted} />
      </Pressable>
      {expanded ? <View style={styles.body}>{children}</View> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { marginTop: spacing.md },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: spacing.sm },
  title: { fontSize: 14, fontWeight: '600' },
  body: { gap: spacing.sm, paddingTop: spacing.xs },
});
