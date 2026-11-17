import { Pressable, StyleSheet, Text } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { spacing, useTheme } from '../../theme';
import type { Budget } from '../../types';
import { BudgetSheet } from './BudgetSheet';

/** The three-dot menu: the same three actions as the web row menu. */
export function BudgetActionsSheet({ budget, visible, onClose, onEdit, onSeeTransactions, onRemove }: {
  budget: Budget | null;
  visible: boolean;
  onClose: () => void;
  onEdit: () => void;
  onSeeTransactions: () => void;
  onRemove: () => void;
}) {
  const c = useTheme();
  const rows = [
    { icon: 'create-outline', label: 'Edit limit', onPress: onEdit, color: c.ink },
    { icon: 'list-outline', label: 'See transactions', onPress: onSeeTransactions, color: c.ink },
    { icon: 'trash-outline', label: 'Remove budget', onPress: onRemove, color: c.dangerInk },
  ] as const;
  return (
    <BudgetSheet visible={visible} title={budget?.categoryName} onClose={onClose}>
      {rows.map((row) => (
        <Pressable key={row.label} onPress={row.onPress} style={styles.row} accessibilityRole="button" accessibilityLabel={row.label}>
          <Ionicons name={row.icon} size={20} color={row.color} />
          <Text style={[styles.rowText, { color: row.color }]}>{row.label}</Text>
        </Pressable>
      ))}
    </BudgetSheet>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, minHeight: 48, paddingHorizontal: spacing.sm },
  rowText: { fontSize: 15, fontWeight: '600' },
});
