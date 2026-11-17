import type { ReactNode } from 'react';
import { KeyboardAvoidingView, Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppModal } from '../AppModal';
import { GlassSurface } from '../GlassSurface';
import { radius, spacing, typography, useTheme } from '../../theme';

/**
 * Bottom-sheet shell shared by the budget menu and the two budget forms: scrim, glass panel, grab
 * handle, keyboard avoidance. Same layering as QuickActionSheet / CategoryEditSheet.
 * Only one of these is ever visible at a time (BudgetsScreen swaps them as siblings); a sheet
 * nested inside another sheet and closed together with it has frozen iOS touches before.
 */
export function BudgetSheet({ visible, title, onClose, closeDisabled = false, children }: {
  visible: boolean; title?: string; onClose: () => void; closeDisabled?: boolean; children: ReactNode;
}) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const close = closeDisabled ? () => {} : onClose;
  return (
    <AppModal visible={visible} transparent animationType="slide" onRequestClose={close}>
      <KeyboardAvoidingView style={styles.flex} behavior="padding">
        <Pressable style={styles.backdrop} onPress={close} accessibilityLabel="Close" accessibilityRole="button" />
        <GlassSurface style={[styles.sheet, { paddingBottom: insets.bottom + spacing.md }]}>
          <View style={[styles.handle, { backgroundColor: c.border }]} />
          {title ? <Text accessibilityRole="header" style={[typography.cardTitle, styles.title, { color: c.ink }]}>{title}</Text> : null}
          {children}
        </GlassSurface>
      </KeyboardAvoidingView>
    </AppModal>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.4)' },
  sheet: { borderTopLeftRadius: radius.xl, borderTopRightRadius: radius.xl, padding: spacing.md, gap: spacing.ms },
  handle: { alignSelf: 'center', width: 36, height: 5, borderRadius: 3 },
  title: { paddingHorizontal: spacing.xs },
});
