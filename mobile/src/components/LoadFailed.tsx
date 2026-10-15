import { Pressable, StyleSheet, Text, View } from 'react-native';
import { spacing, useTheme } from '../theme';

/**
 * A first load that failed, and the one way back from it: "Try again", which refetches the same
 * query. The message is each screen's own existing wording; `tone` keeps its existing colour
 * (muted on the whole-screen failures, dangerInk on most inline ones) and `variant` its layout
 * (centred 14 for a whole screen, left-aligned 13 inside a section).
 */
export function LoadFailed({ message, onRetry, variant = 'inline', tone = 'muted' }: {
  message: string;
  onRetry: () => void;
  variant?: 'screen' | 'inline';
  tone?: 'muted' | 'danger';
}) {
  const c = useTheme();
  const screenSized = variant === 'screen';
  return (
    <View style={screenSized ? styles.screen : styles.inline}>
      <Text
        style={[
          screenSized ? styles.screenMessage : styles.inlineMessage,
          { color: tone === 'danger' ? c.dangerInk : c.muted },
        ]}
      >
        {message}
      </Text>
      <Pressable onPress={onRetry} accessibilityRole="button" style={styles.retry} hitSlop={8}>
        <Text style={[styles.retryText, { color: c.primary }]}>Try again</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { alignItems: 'center', gap: spacing.xs },
  inline: { alignItems: 'flex-start', paddingVertical: spacing.xs },
  screenMessage: { fontSize: 14, textAlign: 'center' },
  inlineMessage: { fontSize: 13 },
  // 44pt: the touch-target floor ReferralsScreen's own retry already meets.
  retry: { minHeight: 44, justifyContent: 'center' },
  retryText: { fontSize: 13, fontWeight: '600' },
});
