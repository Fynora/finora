import { StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { radius, spacing, useTheme } from '../theme';

// react-navigation's bottom-tabs default content height, rounded up -- ToastProvider (App.tsx)
// mounts above RootNavigator, outside the Tab.Navigator tree, so useBottomTabBarHeight() isn't
// reachable here to get the real, exact figure. Every current caller of showToast fires from a
// screen inside the tab bar (DashboardScreen), so without this the toast renders low enough to
// visually overlap the tab bar's icons instead of floating above them.
const APPROX_TAB_BAR_HEIGHT = 56;

export function Toast({ title, body }: { title: string; body?: string }) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <View pointerEvents="none" style={[styles.wrap, { bottom: insets.bottom + APPROX_TAB_BAR_HEIGHT + spacing.sm }]}>
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
