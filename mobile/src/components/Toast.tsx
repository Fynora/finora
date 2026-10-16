import { StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { radius, spacing, useTheme } from '../theme';
import { pillCoverHeight } from '../navigation/tabBarLayout';

export type ToastTone = 'success' | 'error';

export function Toast({ title, body, tone = 'success' }: { title: string; body?: string; tone?: ToastTone }) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const error = tone === 'error';
  return (
    // ToastProvider (App.tsx) mounts outside the tab navigator, so it cannot read the room the
    // floating tab pill takes from context; it uses the pill's own geometry instead and floats
    // just above it. Every current caller of showToast fires from a screen inside the tabs.
    <View testID="toast" pointerEvents="none" style={[styles.wrap, { bottom: pillCoverHeight(insets.bottom) + spacing.sm }]}>
      {/* An error is the same place and size, in the danger colours the app's error banners use,
          and without the tick: a failure must never read as a success. */}
      <View
        style={[styles.card, { backgroundColor: error ? c.dangerBg : c.primaryDark }]}
        accessibilityLiveRegion="polite"
      >
        <Text style={[styles.title, { color: error ? c.dangerInk : c.onPrimary }]}>{error ? title : `✓ ${title}`}</Text>
        {body ? <Text style={[styles.body, { color: error ? c.dangerInk : c.primaryLight }]}>{body}</Text> : null}
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
