import { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '../../components/Button';
import { MetricTile } from '../../components/AccountUI';
import { gmailApi } from '../../api/endpoints';
import { toUserMessage } from '../../lib/apiError';
import { fmtRelativeTime } from '../../lib/format';
import { connectGmail } from '../../lib/gmailAuth';
import { useSingleFlight } from '../../lib/useSingleFlight';
import type { MoreStackParamList } from '../../navigation/types';
import { radius, spacing, useTheme } from '../../theme';

// D-19 Step 1 parity (Trust Center): mirrors frontend/src/pages/Settings.tsx's identical
// SCOPE_LABELS -- `openid` has no user-meaningful description of its own (it's what makes `sub`
// available, not a capability over the user's data), so it's skipped rather than shown as a raw
// URI. "Read Gmail messages", not "read receipts only": gmail.readonly is what Google's consent
// screen actually grants at the OAuth layer -- the trusted-sender gate is Fynora's own policy on
// top of that, not something this scope itself enforces.
const SCOPE_LABELS: Record<string, string> = {
  'https://www.googleapis.com/auth/gmail.readonly': 'Read Gmail messages',
  'https://www.googleapis.com/auth/userinfo.email': 'See your email address',
};

function permissionLabels(scopes: string[]): string[] {
  return scopes.map((s) => SCOPE_LABELS[s]).filter((label): label is string => !!label);
}

function lastSyncedLabel(lastDiscoveryAt: string | null): string {
  const label = fmtRelativeTime(lastDiscoveryAt);
  return label ? `Last synced ${label}` : 'Never synced yet';
}

/**
 * Mobile counterpart to frontend/src/pages/Settings.tsx's "Connected Apps" card -- same states
 * (unavailable / needs-reconnect / disconnected / connected), same actions (connect, sync now,
 * disconnect, review). The one structural difference is the connect flow itself: web does a real
 * top-level navigation to Google's consent screen and comes back via the ?gmail= query param on
 * a page reload; mobile has no page reload to come back to, so connectGmail() (lib/gmailAuth.ts)
 * opens an in-app auth session and hands back the same outcome directly, in memory, once it
 * resolves -- see that function's own doc comment for the full mechanics.
 */
export function GmailConnectionSection() {
  const c = useTheme();
  const navigation = useNavigation<NativeStackNavigationProp<MoreStackParamList>>();
  const queryClient = useQueryClient();
  const singleFlight = useSingleFlight();
  const [connecting, setConnecting] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [disconnecting, setDisconnecting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [actionNotice, setActionNotice] = useState<string | null>(null);
  const [syncError, setSyncError] = useState<string | null>(null);

  const { data: status, isLoading, isError } = useQuery({
    queryKey: ['gmail-status'],
    queryFn: () => gmailApi.status(),
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['gmail-status'] });
  }

  async function connect() {
    setActionError(null);
    setActionNotice(null);
    await singleFlight(async () => {
      setConnecting(true);
      try {
        const outcome = await connectGmail();
        if (outcome === 'connected') {
          setActionNotice('Gmail connected.');
        } else if (outcome === 'declined') {
          setActionNotice('Gmail connection was cancelled.');
        } else if (outcome === 'failed') {
          setActionError("Couldn't connect Gmail -- please try again.");
        }
        // 'cancelled' (the auth session itself was dismissed before reaching Google) gets neither
        // -- the user closed a browser tab, which needs no message of its own.
        refresh();
      } catch (e) {
        setActionError(toUserMessage(e, "Couldn't start the Gmail connection -- please try again."));
      } finally {
        setConnecting(false);
      }
    });
  }

  async function syncNow() {
    setSyncError(null);
    await singleFlight(async () => {
      setSyncing(true);
      try {
        await gmailApi.syncNow();
        refresh();
      } catch (e) {
        setSyncError(toUserMessage(e, "Gmail sync didn't complete -- try again in a moment."));
      } finally {
        setSyncing(false);
      }
    });
  }

  async function disconnect() {
    setActionError(null);
    await singleFlight(async () => {
      setDisconnecting(true);
      try {
        await gmailApi.disconnect();
        refresh();
      } catch (e) {
        setActionError(toUserMessage(e, "Couldn't disconnect Gmail -- please try again."));
      } finally {
        setDisconnecting(false);
      }
    });
  }

  if (isLoading) {
    return <ActivityIndicator color={c.primary} style={styles.loader} />;
  }
  if (isError && !status) {
    return (
      <Text style={[styles.error, { color: c.danger }]}>
        Couldn&apos;t load your Gmail connection — please try again later.
      </Text>
    );
  }
  if (!status?.available) {
    return <Text style={[styles.italic, { color: c.muted }]}>Gmail sync isn&apos;t available on this deployment yet.</Text>;
  }

  if (!status.connected && status.needsReconnect) {
    return (
      <View style={[styles.box, { borderColor: c.warning }]}>
        <View style={styles.row}>
          <View style={styles.rowMain}>
            <View style={styles.titleRow}>
              <Text style={[styles.title, { color: c.ink }]}>Gmail</Text>
              <View style={[styles.badge, { backgroundColor: c.warningBg }]}>
                <Text style={[styles.badgeText, { color: c.warningInk }]}>Needs reconnect</Text>
              </View>
            </View>
            {status.googleEmail ? (
              <Text style={[styles.meta, { color: c.muted }]} numberOfLines={1}>{status.googleEmail}</Text>
            ) : null}
            <Text style={[styles.meta, { color: c.muted }]}>
              Google stopped accepting this connection -- reconnect to keep finding receipts.
            </Text>
          </View>
          <Button label="Reconnect Gmail" onPress={() => void connect()} loading={connecting} />
        </View>
        {actionError ? <Text style={[styles.error, { color: c.danger }]}>{actionError}</Text> : null}
      </View>
    );
  }

  if (!status.connected) {
    return (
      <View>
        <View style={styles.row}>
          <View style={styles.rowMain}>
            <Text style={[styles.title, { color: c.ink }]}>Gmail</Text>
            <Text style={[styles.meta, { color: c.muted }]}>
              Automatically detect receipts from your inbox — nothing is imported without your review.
            </Text>
          </View>
          <Button label="Connect Gmail" onPress={() => void connect()} loading={connecting} />
        </View>
        {actionNotice ? <Text style={[styles.notice, { color: c.success }]}>{actionNotice}</Text> : null}
        {actionError ? <Text style={[styles.error, { color: c.danger }]}>{actionError}</Text> : null}
      </View>
    );
  }

  const labels = permissionLabels(status.grantedScopes);

  return (
    <View style={[styles.box, { borderColor: c.border }]}>
      <View style={styles.row}>
        <View style={styles.rowMain}>
          <View style={styles.titleRow}>
            <Text style={[styles.title, { color: c.ink }]}>Gmail</Text>
            <View style={[styles.badge, { backgroundColor: c.successBg }]}>
              <Text style={[styles.badgeText, { color: c.successInk }]}>Connected</Text>
            </View>
          </View>
          <Text style={[styles.meta, { color: c.muted }]} numberOfLines={1}>{status.googleEmail}</Text>
          <Text style={[styles.meta, { color: c.muted }]}>{lastSyncedLabel(status.lastDiscoveryAt)}</Text>
          {labels.length > 0 ? (
            <Text style={[styles.meta, { color: c.muted }]}>
              <Text style={{ color: c.ink }}>Permissions: </Text>
              {labels.join(', ')} — never sent, modified, or deleted
            </Text>
          ) : null}
        </View>
        {syncing ? (
          <ActivityIndicator color={c.muted} />
        ) : (
          <Pressable
            onPress={() => void syncNow()}
            hitSlop={10}
            accessibilityRole="button"
            accessibilityLabel="Sync Gmail now"
          >
            <Ionicons name="refresh" size={18} color={c.muted} />
          </Pressable>
        )}
      </View>

      <View style={styles.metrics}>
        <MetricTile label="Transactions Found" value={status.transactionsFound.toLocaleString('en-IN')} />
        <MetricTile label="Needs Review" value={status.needsReview.toLocaleString('en-IN')} />
      </View>

      {syncError ? <Text style={[styles.error, { color: c.danger }]}>{syncError}</Text> : null}
      {actionError ? <Text style={[styles.error, { color: c.danger }]}>{actionError}</Text> : null}

      <View style={[styles.actions, { borderTopColor: c.border }]}>
        {status.needsReview > 0 ? (
          <Button
            label={`Review ${status.needsReview}`}
            onPress={() => navigation.navigate('GmailReview')}
          />
        ) : null}
        <Button label="Disconnect" variant="link" onPress={() => void disconnect()} loading={disconnecting} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  loader: { paddingVertical: spacing.sm },
  error: { fontSize: 12, marginTop: spacing.xs },
  notice: { fontSize: 12, marginTop: spacing.xs },
  italic: { fontSize: 12, fontStyle: 'italic' },
  box: { borderWidth: 1, borderRadius: radius.md, padding: 12 },
  row: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between', gap: spacing.sm },
  rowMain: { flex: 1, gap: 2 },
  titleRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
  title: { fontSize: 14, fontWeight: '600' },
  badge: { borderRadius: 4, paddingHorizontal: 6, paddingVertical: 2 },
  badgeText: { fontSize: 10, fontWeight: '600', textTransform: 'uppercase', letterSpacing: 0.3 },
  meta: { fontSize: 11, lineHeight: 15 },
  metrics: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.sm },
  actions: {
    flexDirection: 'row', alignItems: 'center', gap: spacing.md,
    marginTop: spacing.sm, paddingTop: spacing.sm, borderTopWidth: StyleSheet.hairlineWidth,
  },
});
