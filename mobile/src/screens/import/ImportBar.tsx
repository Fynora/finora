import { Pressable, StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native';
import { Button } from '../../components/Button';
import { radius, shadows, spacing, typography, useTheme } from '../../theme';

/**
 * The Import check step's pinned bar (spec 2026-11-02, Step 2): always in reach above the tab bar,
 * saying what stands between the person and Import, or how many marked rows are left to look at.
 * Whether the button is enabled is the caller's decision (canConfirmImport), never this bar's.
 */
export function ImportBar({
  message, buttonLabel, disabled, loading, onImport, onShowMe, onLayoutHeight, style,
}: {
  message: { kind: 'blocked' | 'check'; text: string } | null;
  buttonLabel: string;
  disabled: boolean;
  loading: boolean;
  onImport: () => void;
  onShowMe: () => void;
  onLayoutHeight: (height: number) => void;
  style?: StyleProp<ViewStyle>;
}) {
  const c = useTheme();
  return (
    <View
      testID="import-bar"
      onLayout={(e) => onLayoutHeight(e.nativeEvent.layout.height)}
      style={[styles.bar, { boxShadow: shadows.card, backgroundColor: c.card, borderColor: c.border }, style]}
    >
      {message ? (
        <View style={styles.messageRow} testID="import-bar-message">
          <Text
            accessibilityRole="text"
            style={[typography.labelS, styles.message, { color: message.kind === 'blocked' ? c.warningInk : c.ink }]}
          >
            {message.text}
          </Text>
          {message.kind === 'check' ? (
            <Pressable onPress={onShowMe} hitSlop={8} accessibilityRole="button" accessibilityLabel="Show me">
              <Text style={[typography.labelS, { color: c.primary }]}>Show me</Text>
            </Pressable>
          ) : null}
        </View>
      ) : null}
      <Button label={buttonLabel} onPress={onImport} disabled={disabled} loading={loading} />
    </View>
  );
}

const styles = StyleSheet.create({
  bar: { borderWidth: 1, borderRadius: radius.xl, padding: spacing.sm, gap: spacing.sm },
  messageRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingHorizontal: spacing.xs },
  message: { flex: 1 },
});
