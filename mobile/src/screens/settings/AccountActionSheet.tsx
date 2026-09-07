import type { ReactNode } from 'react';
import {
  KeyboardAvoidingView, Modal, Platform, Pressable, ScrollView, StyleSheet, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { radius, spacing, useTheme } from '../../theme';

interface Props {
  onClose: () => void;
  /** Whether the backdrop tap, hardware back button, and Cancel button can currently dismiss the
   *  sheet -- callers pass `!submitting` so an in-flight request can't be dismissed mid-air. */
  dismissable: boolean;
  /** Distinct per caller ("Close delete account", "Close export my data", ...) rather than a
   *  generic "Close" -- lets a screen reader user tell which sheet they're dismissing when more
   *  than one of these exists in the app. */
  closeLabel: string;
  children: ReactNode;
}

/**
 * The bottom-sheet chrome shared by DeactivateAccountSheet, DeleteAccountSheet, and
 * ExportDataSheet -- Modal + KeyboardAvoidingView + backdrop + sheet + scroll, which were three
 * byte-identical copies of this exact structure before being pulled out here (review, Phase 0).
 * `sheetStyles` alongside this holds the further style keys (title/body/error/action/notice/
 * noticeText) that were ALSO identical in every one -- each sheet still defines whatever's
 * genuinely its own on top of these (DeactivateAccountSheet's picker styles, DeleteAccountSheet's
 * warnBox/checkbox/confirm styles).
 */
export function AccountActionSheet({ onClose, dismissable, closeLabel, children }: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();

  return (
    <Modal visible animationType="slide" transparent onRequestClose={dismissable ? onClose : () => {}}>
      <KeyboardAvoidingView style={chromeStyles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Pressable
          style={chromeStyles.backdrop}
          onPress={dismissable ? onClose : undefined}
          disabled={!dismissable}
          accessibilityLabel={closeLabel}
        />
        <View style={[chromeStyles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}>
          <ScrollView keyboardShouldPersistTaps="handled" style={chromeStyles.scroll}>
            {children}
          </ScrollView>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

const chromeStyles = StyleSheet.create({
  flex: { flex: 1 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)' },
  sheet: {
    maxHeight: '88%',
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    paddingHorizontal: spacing.md,
    paddingTop: spacing.md,
  },
  scroll: { flexGrow: 0 },
});

/** Shared by every AccountActionSheet caller -- title/description/error/button-row/Google-Apple-
 *  notice styling that was identical in all three before this was pulled out. */
export const sheetStyles = StyleSheet.create({
  title: { fontSize: 17, fontWeight: '700', marginBottom: 4 },
  body: { fontSize: 13, lineHeight: 19, marginBottom: spacing.md },
  error: { fontSize: 13, marginBottom: spacing.sm },
  action: { marginTop: spacing.sm, gap: spacing.xs },
  notice: { borderRadius: radius.md, padding: 12, marginBottom: spacing.sm, gap: 4 },
  noticeText: { fontSize: 12, lineHeight: 17 },
});
