import { useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import axios from 'axios';
import { AuthScreenLayout } from '../components/AuthScreenLayout';
import { Button } from '../components/Button';
import { SkeletonBox, SkeletonFormField, SkeletonGroup, SkeletonLines, SkeletonText } from '../components/skeletons/Skeletons';
import { TextField } from '../components/TextField';
import { suspensionApi, type SuspensionRequest, type SuspensionStatus } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { reportTransportFailure, requestStartedAt } from '../lib/monitoring';
import { SUSPENDED_PAGE_TIMED_OUT } from '../lib/suspension';
import { useSingleFlight } from '../lib/useSingleFlight';
import { spacing, useTheme } from '../theme';
import type { AuthStackParamList } from '../navigation/types';
import { ExportDataSheet } from './settings/ExportDataSheet';

type Props = NativeStackScreenProps<AuthStackParamList, 'SuspendedAccount'>;

const OPEN = new Set<SuspensionRequest['status']>(['OPEN', 'IN_PROGRESS']);
const MAX_TEXT = 4000;

function openRequest(status: SuspensionStatus | null, type: SuspensionRequest['type']) {
  return status?.requests.find((r) => r.type === type && OPEN.has(r.status)) ?? null;
}

function formatDate(iso: string | null) {
  if (!iso) return null;
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleDateString('en-IN', { day: 'numeric', month: 'long', year: 'numeric' });
}

function isExpired(err: unknown) {
  return axios.isAxiosError(err) && err.response?.status === 401;
}

/**
 * Privacy workstream 10. Opened in place of a sign-in error when the backend refuses a suspended
 * customer's sign-in (AUTH_018). Mirrors the web app's SuspendedAccountScreen: why the account is
 * suspended, an appeal, a deletion request, and a copy of the account's data.
 *
 * `token` is the 15-minute token that refusal carried. Every call here sends it on that request
 * only (suspensionApi), so it is never the app's session and never stored. The screen's data is held
 * in component state rather than React Query, whose cache is partly persisted to disk. When the
 * token expires, what was typed stays in the field and the screen says to sign in again.
 */
export function SuspendedAccountScreen({ navigation, route }: Props) {
  const c = useTheme();
  const { token } = route.params;
  const singleFlight = useSingleFlight();

  const [status, setStatus] = useState<SuspensionStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [appealText, setAppealText] = useState('');
  const [confirmDeletion, setConfirmDeletion] = useState(false);
  const [sending, setSending] = useState(false);
  const [showExport, setShowExport] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const startedAt = requestStartedAt();
    suspensionApi.status(token).then(
      (result) => {
        if (cancelled) return;
        setStatus(result);
        setLoading(false);
      },
      (err) => {
        if (cancelled) return;
        reportTransportFailure(err, 'suspended:status', startedAt);
        setLoadFailed(true);
        setLoading(false);
      }
    );
    return () => {
      cancelled = true;
    };
  }, [token]);

  async function raise(type: 'APPEAL' | 'DELETION', text?: string) {
    await singleFlight(async () => {
      setSending(true);
      setError(null);
      const startedAt = requestStartedAt();
      try {
        const result = await suspensionApi.raiseRequest(token, type, text);
        setStatus(result);
        setAppealText('');
        setConfirmDeletion(false);
      } catch (err) {
        reportTransportFailure(err, `suspended:${type.toLowerCase()}`, startedAt);
        // A 401 is the token running out. The appeal text is left as typed so it can be copied.
        setError(isExpired(err) ? SUSPENDED_PAGE_TIMED_OUT : toUserMessage(err, 'Could not send that. Please try again.'));
      } finally {
        setSending(false);
      }
    });
  }

  async function exportWithToken(password: string | null, googleIdToken: string | null, appleIdToken: string | null) {
    try {
      await suspensionApi.exportData(token, password, googleIdToken, appleIdToken);
    } catch (err) {
      // The sheet shows the server's message; an expired token says why in plain words instead.
      if (axios.isAxiosError(err) && err.response && err.response.status === 401) {
        err.response.data = { ...(err.response.data as object), message: SUSPENDED_PAGE_TIMED_OUT };
      }
      throw err;
    }
  }

  const appeal = openRequest(status, 'APPEAL');
  const deletion = openRequest(status, 'DELETION');
  const since = formatDate(status?.suspendedAt ?? null);

  return (
    <AuthScreenLayout title="Your account is suspended" error={error}>
      <View testID="suspended-account-screen">
        {loading ? (
          // The screen's own first load: its shape (intro, the "Why" panel, the appeal form), not a spinner.
          <SkeletonGroup testID="suspended-account-skeleton">
            <View style={styles.skeletonGap}>
              <SkeletonLines count={2} size="label" />
            </View>
            <View style={[styles.panel, { backgroundColor: c.dangerBg }]}>
              <SkeletonText size="label" width="20%" />
              <SkeletonLines count={2} size="label" />
            </View>
            <SkeletonText size="body" width="25%" style={styles.skeletonHeading} />
            <SkeletonFormField />
            <SkeletonBox size="button" />
          </SkeletonGroup>
        ) : null}
        {loadFailed && !status ? (
          <Text style={[styles.body, { color: c.dangerInk }]}>
            We couldn&apos;t load the details. Sign in again to see them.
          </Text>
        ) : null}

        {status ? (
          <>
            <Text style={[styles.body, { color: c.muted }]}>
              {since ? `Since ${since}, you can't use Fynora.` : "You can't use Fynora while your account is suspended."}
              {' '}This page works for 15 minutes after you sign in.
            </Text>

            <View style={[styles.panel, { backgroundColor: c.dangerBg }]}>
              <Text style={[styles.panelTitle, { color: c.ink }]}>Why</Text>
              <Text style={[styles.panelText, { color: c.ink }]}>{status.reasonText}</Text>
              {status.message ? (
                <Text style={[styles.panelText, styles.panelGap, { color: c.ink }]}>
                  Message from Fynora: {status.message}
                </Text>
              ) : null}
            </View>

            {status.storePlatform ? (
              <View style={[styles.panel, { backgroundColor: c.warningBg }]}>
                <Text style={[styles.small, { color: c.ink }]}>
                  Your Plus subscription is paid through {status.storePlatform === 'IOS' ? 'the App Store' : 'Google Play'}.
                  Fynora can&apos;t stop it, so cancel it there if you don&apos;t want to be charged again.
                </Text>
              </View>
            ) : null}

            <Text style={[styles.heading, { color: c.ink }]} accessibilityRole="header">Appeal</Text>
            {appeal ? (
              <Text style={[styles.body, { color: c.muted }]}>
                Your appeal ({appeal.ticketNumber}) is with us. We&apos;ll email you when we&apos;ve reviewed it.
              </Text>
            ) : (
              <>
                <TextField
                  label="Tell us why you think this is a mistake."
                  value={appealText}
                  onChangeText={setAppealText}
                  maxLength={MAX_TEXT}
                  multiline
                  numberOfLines={4}
                  textAlignVertical="top"
                  testID="suspended-appeal-text"
                />
                <Button
                  label={sending && !confirmDeletion ? 'Sending…' : 'Send appeal'}
                  onPress={() => void raise('APPEAL', appealText.trim())}
                  disabled={appealText.trim() === '' || sending}
                  loading={sending && !confirmDeletion}
                  testID="suspended-send-appeal"
                />
              </>
            )}

            <Text style={[styles.heading, { color: c.ink }]} accessibilityRole="header">Your data</Text>
            <Button
              label="Download my data"
              variant="secondary"
              onPress={() => setShowExport(true)}
              testID="suspended-download-data"
            />

            <Text style={[styles.heading, { color: c.ink }]} accessibilityRole="header">Delete your account</Text>
            {deletion ? (
              <Text style={[styles.body, { color: c.muted }]}>
                Your deletion request ({deletion.ticketNumber}) is with us. We&apos;ll email you when it&apos;s done.
              </Text>
            ) : confirmDeletion ? (
              <>
                <Text style={[styles.body, { color: c.muted }]}>
                  We&apos;ll delete your account and its data once we&apos;ve checked nothing has to be kept by law.
                  Download your data first if you want a copy.
                </Text>
                <Button
                  label={sending ? 'Sending…' : 'Send deletion request'}
                  onPress={() => void raise('DELETION')}
                  disabled={sending}
                  loading={sending}
                  testID="suspended-confirm-deletion"
                />
                <View style={styles.centerRow}>
                  <Button label="Cancel" variant="link" onPress={() => setConfirmDeletion(false)} disabled={sending} />
                </View>
              </>
            ) : (
              <View style={styles.centerRow}>
                <Button
                  label="Ask us to delete my account"
                  variant="link"
                  onPress={() => setConfirmDeletion(true)}
                  testID="suspended-ask-deletion"
                />
              </View>
            )}
          </>
        ) : null}

        <View style={[styles.centerRow, styles.back]}>
          <Button label="Back to sign in" variant="link" onPress={() => navigation.goBack()} disabled={sending} />
        </View>
      </View>

      {showExport && status ? (
        <ExportDataSheet
          signInMethod={status.signInMethod}
          onClose={() => setShowExport(false)}
          exportData={exportWithToken}
        />
      ) : null}
    </AuthScreenLayout>
  );
}

const styles = StyleSheet.create({
  skeletonGap: {
    marginBottom: spacing.md,
  },
  skeletonHeading: {
    marginTop: spacing.sm,
    marginBottom: spacing.xs,
  },
  body: {
    fontSize: 13,
    lineHeight: 20,
    marginBottom: spacing.md,
  },
  small: {
    fontSize: 12,
    lineHeight: 18,
  },
  panel: {
    borderRadius: 8,
    padding: 12,
    marginBottom: spacing.md,
  },
  panelTitle: {
    fontSize: 13,
    fontWeight: '600',
  },
  panelText: {
    fontSize: 13,
    lineHeight: 19,
  },
  panelGap: {
    marginTop: spacing.sm,
  },
  heading: {
    fontSize: 14,
    fontWeight: '600',
    marginTop: spacing.sm,
    marginBottom: spacing.xs,
  },
  centerRow: {
    alignItems: 'center',
    marginTop: spacing.sm,
  },
  back: {
    marginTop: spacing.md,
  },
});
