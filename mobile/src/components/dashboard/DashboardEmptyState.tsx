import type { ComponentProps } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { IconWell } from './IconWell';
import { spacing, typography, useTheme } from '../../theme';

type IoniconName = ComponentProps<typeof Ionicons>['name'];

/** The dashboard's "nothing here yet". Card.tsx's EmptyState stays for the rest of the app. */
export function DashboardEmptyState({
  icon, message, actionLabel, onAction,
}: { icon: IoniconName; message: string; actionLabel?: string; onAction?: () => void }) {
  const c = useTheme();
  return (
    <View style={styles.wrap}>
      <IconWell name={icon} round size={48} />
      <Text style={[typography.bodyM, styles.message, { color: c.mutedInk }]}>{message}</Text>
      {actionLabel && onAction ? (
        <Pressable onPress={onAction} style={[styles.action, { backgroundColor: c.primary }]} accessibilityRole="button">
          <Text style={[typography.labelS, { color: c.onPrimary }]}>{actionLabel}</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { alignItems: 'center', gap: spacing.ms, paddingVertical: spacing.sm },
  message: { textAlign: 'center' },
  action: { minHeight: 44, paddingHorizontal: spacing.md, borderRadius: 999, alignItems: 'center', justifyContent: 'center' },
});
