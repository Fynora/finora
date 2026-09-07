import { useState } from 'react';
import {
  KeyboardAvoidingView, Modal, Platform, Pressable, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button } from '../../components/Button';
import { TextField } from '../../components/TextField';
import { accountLifecycleApi } from '../../api/endpoints';
import { toUserMessage } from '../../lib/apiError';
import { useSingleFlight } from '../../lib/useSingleFlight';
import { radius, spacing, useTheme } from '../../theme';

/**
 * "Download My Data" (Phase C), ported from frontend/src/components/ExportDataModal.tsx --
 * current password only, same re-auth tier as DeactivateAccountSheet, not the OTP tier
 * DeleteAccountSheet uses (see AccountLifecycleDtos.ExportDataRequest's own doc comment on the
 * backend for why).
 *
 * accountLifecycleApi.exportData() hands the downloaded ZIP straight to the OS share sheet on
 * success (there is no sandboxed "Downloads" folder a mobile user could otherwise browse to), so
 * there is no separate success step here -- the button's own "Preparing…" state is the whole
 * loading UI, and the sheet just closes once the share sheet has been handed off.
 *
 * Google/Apple accounts: see DeactivateAccountSheet's identical doc comment.
 */
export function ExportDataSheet({ onClose, signInMethod, onContactSupport }: {
  onClose: () => void;
  signInMethod: 'PASSWORD' | 'GOOGLE' | 'APPLE';
  onContactSupport: () => void;
}) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const singleFlight = useSingleFlight();

  const [currentPassword, setCurrentPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (currentPassword.length === 0) return;
    setError(null);
    await singleFlight(async () => {
      setSubmitting(true);
      try {
        await accountLifecycleApi.exportData(currentPassword, null);
        onClose();
      } catch (e) {
        setError(toUserMessage(e, 'Could not prepare your export. Please try again.'));
      } finally {
        setSubmitting(false);
      }
    });
  }

  return (
    <Modal visible animationType="slide" transparent onRequestClose={submitting ? () => {} : onClose}>
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Pressable
          style={styles.backdrop}
          onPress={submitting ? undefined : onClose}
          disabled={submitting}
          accessibilityLabel="Close export my data"
        />
        <View style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}>
          <ScrollView keyboardShouldPersistTaps="handled" style={styles.scroll}>
            <Text style={[styles.title, { color: c.ink }]}>Export My Data</Text>
            <Text style={[styles.body, { color: c.muted }]}>
              Downloads a ZIP of everything in your account — accounts, transactions, budgets,
              goals, and your original bank statement files — along with a manifest explaining
              exactly what&apos;s included.
            </Text>

            {signInMethod !== 'PASSWORD' ? (
              <View style={[styles.notice, { backgroundColor: c.primaryLight }]}>
                <Text style={[styles.noticeText, { color: c.ink }]}>
                  Exporting data for a {signInMethod === 'GOOGLE' ? 'Google' : 'Apple'}-linked
                  account isn&apos;t available in the app yet. Contact support and we&apos;ll take
                  care of it.
                </Text>
                <Button label="Contact Support" variant="link" onPress={onContactSupport} />
              </View>
            ) : (
              <>
                <TextField
                  label="Current password"
                  value={currentPassword}
                  onChangeText={(v) => { setCurrentPassword(v); setError(null); }}
                  secure
                  autoCapitalize="none"
                  textContentType="password"
                />
                {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}
                <View style={styles.action}>
                  <Button
                    label={submitting ? 'Preparing your export…' : 'Export My Data'}
                    onPress={() => void submit()}
                    loading={submitting}
                    disabled={currentPassword.length === 0}
                  />
                </View>
              </>
            )}

            <Button label="Cancel" variant="link" onPress={onClose} disabled={submitting} />
          </ScrollView>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

const styles = StyleSheet.create({
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
  title: { fontSize: 17, fontWeight: '700', marginBottom: 4 },
  body: { fontSize: 13, lineHeight: 19, marginBottom: spacing.md },
  error: { fontSize: 13, marginBottom: spacing.sm },
  action: { marginTop: spacing.sm, gap: spacing.xs },
  notice: { borderRadius: radius.md, padding: 12, marginBottom: spacing.sm, gap: 4 },
  noticeText: { fontSize: 12, lineHeight: 17 },
});
