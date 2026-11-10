import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Button } from '../Button';
import { Card } from '../Card';
import { InflowKindPicker } from '../InflowKindPicker';
import { fmtCurrency } from '../../lib/format';
import type { ChoiceScope, InflowKind, UnresolvedSender } from '../../types';
import { spacing, useTheme } from '../../theme';

/**
 * One sender whose payments are not counted as income: tap to open, pick what the money was, then
 * apply it to every payment from them or to one. Shared by Money Review and the import Done step,
 * so the two never ask differently. Mirrors frontend/src/components/inflow/UnresolvedSenderRow.tsx.
 */
export function UnresolvedSenderRow({ sender: s, kinds, open, onToggle, busy, onApply, onCreateKind }: {
  sender: UnresolvedSender;
  kinds: InflowKind[] | undefined;
  open: boolean;
  onToggle: () => void;
  busy: boolean;
  onApply: (transactionId: string, kindId: string, scope: ChoiceScope, label: string) => void;
  onCreateKind: (name: string, countsAsIncome: boolean) => Promise<InflowKind>;
}) {
  const c = useTheme();
  const [picked, setPicked] = useState<InflowKind | null>(null);
  const [oneRow, setOneRow] = useState(false);

  // A row that closes forgets its half-made choice; reset while rendering, not in an effect.
  const [wasOpen, setWasOpen] = useState(open);
  if (open !== wasOpen) {
    setWasOpen(open);
    setPicked(null);
    setOneRow(false);
  }

  return (
    <Card>
      <Pressable accessibilityRole="button" accessibilityState={{ expanded: open }} onPress={onToggle}>
        <View style={styles.row}>
          <Text style={[styles.name, { color: c.ink }]} numberOfLines={1}>{s.label}</Text>
          <Text style={[styles.body, { color: c.ink }]}>{fmtCurrency(s.total)}</Text>
        </View>
        <Text style={[styles.small, { color: c.muted }]}>
          {s.count} {s.count === 1 ? 'payment' : 'payments'} · latest {s.latestDate}{s.accountName ? ` · ${s.accountName}` : ''}
        </Text>
      </Pressable>
      {open && kinds ? (
        <View style={styles.editor}>
          <InflowKindPicker
            kinds={kinds}
            selectedId={picked?.id ?? null}
            onPick={setPicked}
            busy={busy}
            onCreate={async (name, countsAsIncome) => setPicked(await onCreateKind(name, countsAsIncome))}
          />
          {picked && !oneRow ? (
            <View style={styles.editor}>
              {s.senderKnown ? (
                <Button label={`Every payment from ${s.label} (${s.senderPaymentCount})`} loading={busy}
                  onPress={() => onApply(s.sampleTransactionId, picked.id, 'SENDER', s.label)} />
              ) : null}
              <Button label="Just this one" variant={s.senderKnown ? 'link' : 'primary'} disabled={busy}
                onPress={() => (s.rows.length === 1 ? onApply(s.rows[0].id, picked.id, 'ROW', s.label) : setOneRow(true))} />
            </View>
          ) : null}
          {picked && oneRow ? s.rows.map((r) => (
            <Pressable key={r.id} accessibilityRole="button" disabled={busy} onPress={() => onApply(r.id, picked.id, 'ROW', s.label)}
              style={styles.row}>
              <Text style={[styles.body, { color: c.ink }]}>{r.date}</Text>
              <Text style={[styles.body, { color: c.ink }]}>{fmtCurrency(r.amount)}</Text>
            </Pressable>
          )) : null}
        </View>
      ) : null}
    </Card>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.sm, paddingVertical: spacing.xs },
  name: { fontSize: 15, fontWeight: '600', flexShrink: 1 },
  body: { fontSize: 14 },
  small: { fontSize: 12 },
  editor: { gap: spacing.sm, marginTop: spacing.sm },
});
