import { useState } from 'react';
import { Text, View } from 'react-native';
import { Button } from '../../components/Button';
import { TextField } from '../../components/TextField';
import { accountLifecycleApi } from '../../api/endpoints';
import { toUserMessage } from '../../lib/apiError';
import { useSingleFlight } from '../../lib/useSingleFlight';
import { useTheme } from '../../theme';
import { AccountActionSheet, sheetStyles } from './AccountActionSheet';

/**
 * "Download My Data" (Phase C), ported from frontend/src/components/ExportDataModal.tsx --
 * current password only, same re-auth tier as DeactivateAccountSheet, not the OTP tier
 * DeleteAccountSheet uses (see AccountLifecycleDtos.ExportDataRequest's own doc comment on the
 * backend for why).
 *
 * accountLifecycleApi.exportData() hands the downloaded ZIP straight to the OS share sheet on
 * success (there is no sandboxed "Downloads" folder a mobile user could otherwise browse to), so
 * there is no separate success step here -- the button's own "Preparing…" state is the whole
 * loading UI, and the sheet just closes once the share hand-off has completed.
 *
 * Google/Apple accounts: see DeactivateAccountSheet's identical doc comment.
 */
export function ExportDataSheet({ onClose, signInMethod, onContactSupport }: {
  onClose: () => void;
  signInMethod: 'PASSWORD' | 'GOOGLE' | 'APPLE';
  onContactSupport: () => void;
}) {
  const c = useTheme();
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
    <AccountActionSheet onClose={onClose} dismissable={!submitting} closeLabel="Close export my data">
      <Text style={[sheetStyles.title, { color: c.ink }]}>Export My Data</Text>
      <Text style={[sheetStyles.body, { color: c.muted }]}>
        Downloads a ZIP of everything in your account — accounts, transactions, budgets, goals,
        and your original bank statement files — along with a manifest explaining exactly what&apos;s
        included.
      </Text>

      {signInMethod !== 'PASSWORD' ? (
        <View style={[sheetStyles.notice, { backgroundColor: c.primaryLight }]}>
          <Text style={[sheetStyles.noticeText, { color: c.ink }]}>
            Exporting data for a {signInMethod === 'GOOGLE' ? 'Google' : 'Apple'}-linked account
            isn&apos;t available in the app yet. Contact support and we&apos;ll take care of it.
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
          {error ? <Text style={[sheetStyles.error, { color: c.danger }]}>{error}</Text> : null}
          <View style={sheetStyles.action}>
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
    </AccountActionSheet>
  );
}
