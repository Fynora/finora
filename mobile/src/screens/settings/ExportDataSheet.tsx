import { useState } from 'react';
import { Text, View } from 'react-native';
import { AppleReauthPrompt } from '../../components/AppleReauthPrompt';
import { Button } from '../../components/Button';
import { GoogleReauthPrompt } from '../../components/GoogleReauthPrompt';
import { TextField } from '../../components/TextField';
import { accountLifecycleApi } from '../../api/endpoints';
import { toUserMessage } from '../../lib/apiError';
import { hapticError, hapticSuccess } from '../../lib/haptics';
import { reportTransportFailure, requestStartedAt } from '../../lib/monitoring';
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
 * Google/Apple accounts have no password anyone knows, so they prove who they are with a fresh
 * Google or Apple credential instead (GoogleReauthPrompt / AppleReauthPrompt, the same step-up
 * ChangePasswordSheet uses); the backend's GoogleReauthVerifier accepts either for export.
 */
export function ExportDataSheet({ onClose, signInMethod, exportData = accountLifecycleApi.exportData }: {
  onClose: () => void;
  signInMethod: 'PASSWORD' | 'GOOGLE' | 'APPLE';
  /** The download itself. The suspended screen passes one that sends its own token (privacy
   *  workstream 10); everywhere else the app's session does. Exactly one credential is non-null. */
  exportData?: (currentPassword: string | null, googleIdToken: string | null, appleIdToken: string | null) => Promise<void>;
}) {
  const c = useTheme();
  const singleFlight = useSingleFlight();

  const [currentPassword, setCurrentPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /** Exactly one of the three is non-null, matching the backend's request contract. */
  async function submitWithCredential(
    currentPasswordArg: string | null, googleIdToken: string | null, appleIdToken: string | null
  ) {
    setError(null);
    await singleFlight(async () => {
      setSubmitting(true);
      const startedAt = requestStartedAt();
      try {
        await exportData(currentPasswordArg, googleIdToken, appleIdToken);
        hapticSuccess();
        onClose();
      } catch (e) {
        hapticError();
        reportTransportFailure(e, 'export-data:submit', startedAt);
        // The server's own message wins when there is one ("We couldn't verify your Google
        // account..." for a token that does not match this account); this is only the fallback.
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

      {signInMethod === 'PASSWORD' ? (
        <>
          <TextField
            label="Current password"
            value={currentPassword}
            onChangeText={(v) => { setCurrentPassword(v); setError(null); }}
            secure
            autoCapitalize="none"
            textContentType="password"
          />
          {error ? <Text style={[sheetStyles.error, { color: c.dangerInk }]}>{error}</Text> : null}
          <View style={sheetStyles.action}>
            <Button
              label={submitting ? 'Preparing your export…' : 'Export My Data'}
              onPress={() => void submitWithCredential(currentPassword, null, null)}
              loading={submitting}
              disabled={currentPassword.length === 0}
            />
          </View>
        </>
      ) : (
        <>
          {/* Kept mounted while the export runs: the button awaits onCredential and shows its
              own locked, dimmed state until it settles. */}
          {signInMethod === 'GOOGLE' ? (
            <GoogleReauthPrompt
              onCredential={(idToken) => submitWithCredential(null, idToken, null)}
              onError={setError}
            />
          ) : (
            <AppleReauthPrompt
              onCredential={(idToken) => submitWithCredential(null, null, idToken)}
              onError={setError}
            />
          )}
          {submitting ? <Text style={[sheetStyles.body, { color: c.muted }]}>Preparing your export…</Text> : null}
          {error ? <Text style={[sheetStyles.error, { color: c.dangerInk }]}>{error}</Text> : null}
        </>
      )}

      <Button label="Cancel" variant="link" onPress={onClose} disabled={submitting} />
    </AccountActionSheet>
  );
}
