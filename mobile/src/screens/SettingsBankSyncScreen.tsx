import { useState } from 'react';
import { ActivityIndicator, Linking, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '../components/Button';
import { accountAggregatorApi, type AccountAggregatorLinkDto } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { useSingleFlight } from '../lib/useSingleFlight';
import { radius, spacing, useTheme } from '../theme';
import type { MoreStackParamList } from '../navigation/types';

const AA_FI_TYPE_LABELS: Record<string, string> = { DEPOSIT: 'Bank Account', CREDIT_CARD: 'Credit Card' };
function aaFiTypeLabel(fiType: string): string { return AA_FI_TYPE_LABELS[fiType] ?? fiType; }

function aaStatusCopy(status: string): string {
  switch (status) {
    case 'ACTIVE': return 'Connected';
    case 'PAUSED': return "Your plan no longer includes Bank Sync -- reconnects automatically once you upgrade";
    case 'CONSENT_PENDING': return 'Waiting for you to approve this in your banking app';
    case 'PENDING_ACCOUNT_CONFIRMATION': return 'Waiting for you to confirm which account this is';
    case 'REJECTED': return 'Declined -- try again';
    case 'LINK_FAILED': return "Couldn't connect -- try again";
    case 'EXPIRED': return 'Your bank connection has expired -- reconnect';
    case 'REVOKED': return 'Disconnected';
    default: return status;
  }
}

const AA_TERMINAL_STATUSES = new Set(['REVOKED', 'EXPIRED', 'REJECTED', 'LINK_FAILED']);

export function SettingsBankSyncScreen() {
  const c = useTheme();
  const navigation = useNavigation<NativeStackNavigationProp<MoreStackParamList>>();
  const queryClient = useQueryClient();
  const singleFlight = useSingleFlight();
  const [connecting, setConnecting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [disconnectingId, setDisconnectingId] = useState<string | null>(null);
  const [confirmingDisconnectId, setConfirmingDisconnectId] = useState<string | null>(null);

  const linksQ = useQuery({ queryKey: ['aa-links'], queryFn: () => accountAggregatorApi.list() });
  const links = linksQ.data ?? [];

  async function handleConnect() {
    setActionError(null);
    await singleFlight(async () => {
      setConnecting(true);
      try {
        const idempotencyKey = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
        const { redirectUrl } = await accountAggregatorApi.initiate('DEPOSIT', idempotencyKey);
        if (redirectUrl) await Linking.openURL(redirectUrl);
        else setActionError('This connection attempt is already in progress.');
      } catch (e) {
        setActionError(toUserMessage(e, "Couldn't start connecting your bank."));
      } finally {
        setConnecting(false);
      }
    });
  }

  async function handleDisconnect(linkId: string) {
    setActionError(null);
    setDisconnectingId(linkId);
    try {
      await accountAggregatorApi.disconnect(linkId);
      setConfirmingDisconnectId(null);
      void queryClient.invalidateQueries({ queryKey: ['aa-links'] });
    } catch (e) {
      setActionError(toUserMessage(e, "Couldn't disconnect."));
    } finally {
      setDisconnectingId(null);
    }
  }

  return (
    <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content}>
      {linksQ.isLoading ? (
        <ActivityIndicator color={c.primary} />
      ) : (
        <View>
          {links.length === 0 ? (
            <Text style={[styles.hint, { color: c.mutedInk }]}>No bank accounts linked yet.</Text>
          ) : (
            links.map((link: AccountAggregatorLinkDto) => (
              <View key={link.id} style={[styles.linkRow, { borderColor: c.border }]}>
                <Text style={[styles.rowTitle, { color: c.ink }]}>{aaFiTypeLabel(link.fiType)} · {link.status.replace(/_/g, ' ')}</Text>
                <Text style={[styles.hint, { color: c.mutedInk }]}>{aaStatusCopy(link.status)}</Text>
                {link.status === 'PENDING_ACCOUNT_CONFIRMATION' && (
                  <Button label="Confirm Account" onPress={() => navigation.navigate('SettingsBankSyncConfirm', { linkId: link.id })} />
                )}
                {!AA_TERMINAL_STATUSES.has(link.status) && confirmingDisconnectId !== link.id && (
                  <Button label="Disconnect" variant="link" onPress={() => setConfirmingDisconnectId(link.id)} />
                )}
                {confirmingDisconnectId === link.id && (
                  <View style={styles.confirmBlock}>
                    <Text style={[styles.hint, { color: c.mutedInk }]}>
                      This does not cancel your consent at your banking app -- it only stops Fynora from syncing this account.
                    </Text>
                    <Button label="Confirm Disconnect" loading={disconnectingId === link.id} onPress={() => void handleDisconnect(link.id)} />
                    <Button label="Cancel" variant="link" onPress={() => setConfirmingDisconnectId(null)} />
                  </View>
                )}
              </View>
            ))
          )}
          {actionError ? <Text style={[styles.hint, { color: c.danger }]}>{actionError}</Text> : null}
          <Button label="Connect a Bank Account" onPress={() => void handleConnect()} loading={connecting} />
        </View>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  linkRow: { borderWidth: 1, borderRadius: radius.md, padding: spacing.sm, marginBottom: spacing.sm },
  rowTitle: { fontSize: 14, fontWeight: '600' },
  hint: { fontSize: 12, marginTop: 4 },
  confirmBlock: { marginTop: spacing.sm, gap: spacing.xs },
});
