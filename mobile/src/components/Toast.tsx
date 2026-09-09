import { StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { radius, spacing, useTheme } from '../theme';

export function Toast({ title, body }: { title: string; body?: string }) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <View pointerEvents="none" style={[styles.wrap, { bottom: insets.bottom + spacing.lg }]}>
      <View style={[styles.card, { backgroundColor: c.primaryDark }]}>
        <Text style={[styles.title, { color: c.onPrimary }]}>✓ {title}</Text>
        {body ? <Text style={[styles.body, { color: c.primaryLight }]}>{body}</Text> : null}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { position: 'absolute', left: spacing.md, right: spacing.md, alignItems: 'center' },
  card: { borderRadius: radius.lg, paddingHorizontal: spacing.md, paddingVertical: spacing.sm, maxWidth: 360, width: '100%' },
  title: { fontSize: 14, fontWeight: '700' },
  body: { fontSize: 12, marginTop: 2 },
});
