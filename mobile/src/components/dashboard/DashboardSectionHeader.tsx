import type { ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useLargeFontScale } from '../../lib/useLargeFontScale';
import { spacing, typography, useTheme } from '../../theme';

/**
 * A dashboard section's title row. One of three things may sit on the right: a plain caption
 * (the comparison period), a pressable action ("See all"), or a caller-supplied accessory (the
 * cash flow range control). Card.tsx's SectionHeading stays as it is for the rest of the app.
 */
export function DashboardSectionHeader({
  title, caption, actionLabel, actionAccessibilityLabel, onAction, showChevron = true, accessory,
}: {
  title: string; caption?: string; actionLabel?: string;
  /** What the action is read out as, when its visible words ("See all") need the card's context. */
  actionAccessibilityLabel?: string;
  onAction?: () => void; showChevron?: boolean; accessory?: ReactNode;
}) {
  const c = useTheme();
  // Beside a caption the title is squeezed as the text grows ("Sep 26" took two lines on a
  // simulator at an accessibility size), so at large text it has the row to itself and whatever
  // sat on its right goes under it.
  const largeText = useLargeFontScale();
  return (
    <View testID={`section-header-${title}`} style={[styles.row, largeText && styles.rowStacked]}>
      <Text accessibilityRole="header" style={[typography.cardTitle, styles.title, { color: c.ink }]}>{title}</Text>
      {accessory}
      {!accessory && actionLabel && onAction ? (
        <Pressable
          onPress={onAction}
          style={styles.action}
          hitSlop={8}
          accessibilityRole="button"
          accessibilityLabel={actionAccessibilityLabel ?? actionLabel}
        >
          <Text style={[typography.labelS, { color: c.mutedInk }]}>{actionLabel}</Text>
          {showChevron ? <Ionicons name="chevron-forward" size={14} color={c.muted} /> : null}
        </Pressable>
      ) : null}
      {!accessory && !onAction && caption ? (
        <Text style={[typography.labelS, styles.caption, largeText && styles.captionStacked, { color: c.mutedInk }]} numberOfLines={2}>{caption}</Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm, minHeight: 24 },
  rowStacked: { flexDirection: 'column', alignItems: 'flex-start', justifyContent: 'flex-start', gap: 0 },
  title: { flexShrink: 1 },
  action: { flexDirection: 'row', alignItems: 'center', gap: 2, minHeight: 44 },
  caption: { flexShrink: 1, textAlign: 'right' },
  captionStacked: { textAlign: 'left' },
});
