import { Modal, Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Ionicons from '@expo/vector-icons/Ionicons';
import { radius, spacing, useTheme } from '../../theme';

interface Props {
  visible: boolean;
  onClose: () => void;
  onImportStatement: () => void;
  onAddTransaction: () => void;
  onAddGoal: () => void;
}

/** The bottom-nav floating "+" button's action sheet. Three destinations, same icons the
 *  existing Quick Actions card already uses for the same actions, for visual consistency. */
export function QuickActionSheet({ visible, onClose, onImportStatement, onAddTransaction, onAddGoal }: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();

  function pick(action: () => void) {
    onClose();
    action();
  }

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <Pressable style={styles.backdrop} onPress={onClose} accessibilityLabel="Close" accessibilityRole="button" />
      <View style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}>
        {(
          [
            { icon: 'cloud-upload-outline', label: 'Import Statement', onPress: () => pick(onImportStatement) },
            { icon: 'add-circle-outline', label: 'Add Transaction', onPress: () => pick(onAddTransaction) },
            { icon: 'flag-outline', label: 'Add Goal', onPress: () => pick(onAddGoal) },
          ] as const
        ).map((row) => (
          <Pressable key={row.label} onPress={row.onPress} style={styles.row} accessibilityRole="button" accessibilityLabel={row.label}>
            <Ionicons name={row.icon} size={20} color={c.primary} />
            <Text style={[styles.rowText, { color: c.ink }]}>{row.label}</Text>
          </Pressable>
        ))}
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.4)' },
  sheet: { borderTopLeftRadius: radius.xl, borderTopRightRadius: radius.xl, padding: spacing.md },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, minHeight: 48, paddingHorizontal: spacing.sm },
  rowText: { fontSize: 15, fontWeight: '600' },
});
