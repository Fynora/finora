import type { ReactNode } from 'react';
import { KeyboardAvoidingView, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppModal } from '../AppModal';
import { GlassSurface } from '../GlassSurface';
import { radius, spacing, typography, useTheme } from '../../theme';

/**
 * Bottom-sheet shell shared by the budget menu and the two budget forms: scrim, glass panel, grab
 * handle, keyboard avoidance. Same layering as QuickActionSheet / CategoryEditSheet.
 * Only one of these is ever visible at a time (BudgetsScreen swaps them as siblings); a sheet
 * nested inside another sheet and closed together with it has frozen iOS touches before.
 *
 * The content scrolls because at the largest text size a form is taller than the space above the
 * keyboard, and the decimal pad has no key to close it: dragging the content does that instead.
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
        <GlassSurface style={[styles.sheet, { marginTop: insets.top, paddingBottom: insets.bottom + spacing.md }]}>
          <View style={[styles.handle, { backgroundColor: c.border }]} />
          <ScrollView
            testID="budget-sheet-scroll"
            style={styles.scroll}
            contentContainerStyle={styles.content}
            keyboardShouldPersistTaps="handled"
            keyboardDismissMode="on-drag"
            bounces={false}
          >
            {title ? <Text accessibilityRole="header" style={[typography.cardTitle, styles.title, { color: c.ink }]}>{title}</Text> : null}
            {children}
          </ScrollView>
        </GlassSurface>
      </KeyboardAvoidingView>
    </AppModal>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.4)' },
  sheet: { flexShrink: 1, borderTopLeftRadius: radius.xl, borderTopRightRadius: radius.xl, padding: spacing.md, gap: spacing.ms },
  handle: { alignSelf: 'center', width: 36, height: 5, borderRadius: 3 },
  // Hugs its content until the content no longer fits; a ScrollView grows to fill by default.
  scroll: { flexGrow: 0, flexShrink: 1 },
  content: { gap: spacing.ms },
  title: { paddingHorizontal: spacing.xs },
});
