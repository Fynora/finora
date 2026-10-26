import { useState } from 'react';
import { KeyboardAvoidingView, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppModal } from '../components/AppModal';
import { Button } from '../components/Button';
import { GlassSurface } from '../components/GlassSurface';
import { TextField } from '../components/TextField';
import { accountsApi } from '../api/endpoints';
import { readAmountOwed } from '../lib/amountOwed';
import { toUserMessage } from '../lib/apiError';
import { hapticError, hapticSuccess } from '../lib/haptics';
import { reportTransportFailure, requestStartedAt } from '../lib/monitoring';
import { useSingleFlight } from '../lib/useSingleFlight';
import { radius, spacing, useTheme } from '../theme';
import type { Account } from '../types';

/**
 * Shown in place of a card's balance when the server says its amount owed is not known: the
 * balance was summed from zero off statements with no closing balance, so no figure is shown.
 * docs/superpowers/specs/2026-10-26-card-amount-owed-prompt-design.md.
 */
export function AmountOwedNotKnown({ cardName, onAdd }: { cardName: string; onAdd: () => void }) {
  const c = useTheme();
  return (
    <View style={styles.notKnownRow}>
      <Text style={[styles.notKnown, { color: c.muted }]}>Amount owed not known</Text>
      <Pressable
        onPress={onAdd}
        accessibilityRole="button"
        accessibilityLabel="Add it"
        accessibilityHint={`Asks how much you owe on ${cardName} today`}
        hitSlop={8}
      >
        <Text style={[styles.addIt, { color: c.primary }]}>Add it</Text>
      </Pressable>
    </View>
  );
}

/**
 * "How much do you owe on this card today?" for a card already on the Accounts screen. Saves
 * through the ordinary account update, which records the figure as typed by the user: rows already
 * on the card, and statements for earlier periods, stop moving it.
 */
export function AddAmountOwedSheet({
  account,
  onClose,
  onSaved,
}: {
  account: Account;
  onClose: () => void;
  onSaved: (updated: Account) => void;
}) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const singleFlight = useSingleFlight();
  const [value, setValue] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const answer = readAmountOwed(value);

  async function save() {
    if (answer.state !== 'valid') return;
    setError(null);
    await singleFlight(async () => {
      setSaving(true);
      const startedAt = requestStartedAt();
      try {
        const updated = await accountsApi.update(account.id, {
          name: account.name,
          accountType: account.accountType,
          balance: answer.value,
        });
        hapticSuccess();
        onSaved(updated);
      } catch (e) {
        hapticError();
        reportTransportFailure(e, 'account-amount-owed:update', startedAt);
        setError(toUserMessage(e, 'Could not save the amount. Try again.'));
      } finally {
        setSaving(false);
      }
    });
  }

  return (
    <AppModal visible animationType="slide" transparent onRequestClose={saving ? () => {} : onClose}>
      <KeyboardAvoidingView style={styles.flex} behavior="padding">
        <Pressable
          style={styles.backdrop}
          onPress={saving ? undefined : onClose}
          disabled={saving}
          accessibilityLabel="Close amount owed"
        />
        <GlassSurface style={[styles.sheet, { paddingBottom: insets.bottom + spacing.md }]}>
          <ScrollView keyboardShouldPersistTaps="handled" style={styles.scroll}>
            <Text style={[styles.title, { color: c.ink }]}>How much do you owe on {account.name} today?</Text>
            <Text style={[styles.help, { color: c.muted }]}>
              Your statements don't show this card's balance. Your card app or latest bill has it.
            </Text>
            <TextField
              label="Amount owed today (₹)"
              value={value}
              onChangeText={(v) => { setValue(v); setError(null); }}
              keyboardType="decimal-pad"
              placeholder="0.00"
              autoFocus
              error={answer.state === 'invalid' ? 'Enter the amount in rupees, like 12345.67' : null}
            />
            {error ? <Text style={[styles.error, { color: c.dangerInk }]}>{error}</Text> : null}
            <View style={styles.action}>
              <Button
                label="Save"
                onPress={() => void save()}
                loading={saving}
                disabled={answer.state !== 'valid' || saving}
              />
              <Button label="Cancel" variant="link" onPress={onClose} disabled={saving} />
            </View>
          </ScrollView>
        </GlassSurface>
      </KeyboardAvoidingView>
    </AppModal>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)' },
  sheet: {
    maxHeight: '90%',
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    paddingHorizontal: spacing.md,
    paddingTop: spacing.md,
  },
  scroll: { flexGrow: 0 },
  title: { fontSize: 17, fontWeight: '700', marginBottom: spacing.xs },
  help: { fontSize: 13, marginBottom: spacing.sm },
  error: { fontSize: 13, marginTop: spacing.xs },
  action: { marginTop: spacing.sm, gap: spacing.xs, marginBottom: spacing.sm },
  notKnownRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, flexWrap: 'wrap' },
  notKnown: { fontSize: 15 },
  addIt: { fontSize: 15, fontWeight: '700' },
});
