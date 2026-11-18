import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Button } from '../Button';
import { OptionPickerModal } from '../OptionPickerModal';
import { TextField } from '../TextField';
import { BudgetSheet } from '../budgets/BudgetSheet';
import { toUserMessage } from '../../lib/apiError';
import { hapticWarning } from '../../lib/haptics';
import { holdingValueProblem, parseHoldingValue, valueChangeLine } from '../../lib/investmentSummary';
import { useSingleFlight } from '../../lib/useSingleFlight';
import { radius, spacing, typography, useTheme } from '../../theme';
import type { Account } from '../../types';

// The values already stored on holdings; the labels people see are the same words.
const HOLDING_KINDS = ['Mutual Fund', 'Stocks', 'FD', 'PPF/NPS', 'Other'];
const NAME_MAX = 120;

function valueError(text: string) {
  return holdingValueProblem(text) ?? 'Current value must be greater than zero.';
}

/** The three-dot menu. A bank-linked holding's value comes from its link, so it can't be typed over. */
export function HoldingActionsSheet({ holding, visible, onClose, onUpdate, onDelete }: {
  holding: Account | null; visible: boolean; onClose: () => void; onUpdate: () => void; onDelete: () => void;
}) {
  const c = useTheme();
  const rows = [
    ...(holding?.primarySource === 'ACCOUNT_AGGREGATOR'
      ? []
      : [{ icon: 'create-outline', label: 'Update value', onPress: onUpdate, color: c.ink }] as const),
    { icon: 'trash-outline', label: 'Delete holding', onPress: onDelete, color: c.dangerInk },
  ] as const;
  return (
    <BudgetSheet visible={visible} title={holding?.name} onClose={onClose}>
      {rows.map((row) => (
        <Pressable key={row.label} onPress={row.onPress} style={styles.menuRow} accessibilityRole="button" accessibilityLabel={row.label}>
          <Ionicons name={row.icon} size={20} color={row.color} />
          <Text style={[styles.menuText, { color: row.color }]}>{row.label}</Text>
        </Pressable>
      ))}
    </BudgetSheet>
  );
}

/** Change what a holding is worth today. Name, type and deposit terms stay as they are. */
export function UpdateValueSheet({ holding, money, onClose, onSave }: {
  holding: Account; money: (n: number) => string; onClose: () => void; onSave: (value: number) => Promise<void>;
}) {
  const c = useTheme();
  const [text, setText] = useState(String(holding.balance));
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const singleFlight = useSingleFlight();
  const change = valueChangeLine(holding.balance, parseHoldingValue(text), money);

  async function save() {
    await singleFlight(async () => {
      const value = parseHoldingValue(text);
      if (value === null) {
        setError(valueError(text));
        hapticWarning();
        return;
      }
      setError(null);
      setSaving(true);
      try {
        await onSave(value);
      } catch (e) {
        setError(toUserMessage(e, 'Could not save this value. Try again.'));
        setSaving(false);
      }
    });
  }

  return (
    <BudgetSheet visible title={holding.name} onClose={onClose} closeDisabled={saving}>
      <TextField
        label="Current value"
        value={text}
        onChangeText={(v) => { setText(v); setError(null); }}
        keyboardType="decimal-pad"
        prefix="₹"
        error={error ?? undefined}
      />
      {change ? <Text style={[typography.bodyS, { color: c.mutedInk }]}>{change}</Text> : null}
      <Button label={saving ? 'Saving…' : 'Save value'} onPress={() => void save()} loading={saving} />
    </BudgetSheet>
  );
}

export function AddHoldingSheet({ onClose, onSave }: {
  onClose: () => void; onSave: (holding: { name: string; balance: number; investmentKind: string }) => Promise<void>;
}) {
  const c = useTheme();
  const [name, setName] = useState('');
  const [text, setText] = useState('');
  const [kind, setKind] = useState(HOLDING_KINDS[0]);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [nameError, setNameError] = useState<string | null>(null);
  const [valueProblem, setValueProblem] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const singleFlight = useSingleFlight();

  async function save() {
    await singleFlight(async () => {
      setFormError(null);
      if (!name.trim()) {
        setNameError('Give this holding a name.');
        hapticWarning();
        return;
      }
      const balance = parseHoldingValue(text);
      if (balance === null) {
        setValueProblem(valueError(text));
        hapticWarning();
        return;
      }
      setSaving(true);
      try {
        await onSave({ name: name.trim(), balance, investmentKind: kind });
      } catch (e) {
        // The one failure a person can act on is the holdings limit, whose message says what to do.
        setFormError(toUserMessage(e, 'Could not add this holding.'));
        setSaving(false);
      }
    });
  }

  return (
    <BudgetSheet visible title="Add holding" onClose={onClose} closeDisabled={saving}>
      <TextField
        label="Name"
        value={name}
        onChangeText={(v) => { setName(v); setNameError(null); }}
        placeholder="Index fund"
        maxLength={NAME_MAX}
        error={nameError ?? undefined}
      />
      <TextField
        label="Current value"
        value={text}
        onChangeText={(v) => { setText(v); setValueProblem(null); }}
        keyboardType="decimal-pad"
        placeholder="0"
        prefix="₹"
        error={valueProblem ?? undefined}
      />
      <View>
        <Text style={[styles.fieldLabel, { color: c.muted }]}>Type</Text>
        <Pressable
          onPress={() => setPickerOpen(true)}
          style={[styles.picker, { backgroundColor: c.inputBg, borderColor: c.border }]}
          accessibilityRole="button"
          accessibilityLabel={`Type: ${kind}. Change`}
        >
          <Text style={[styles.pickerText, { color: c.ink }]}>{kind}</Text>
          <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
        </Pressable>
      </View>
      {formError ? <Text style={[typography.bodyS, { color: c.dangerInk }]}>{formError}</Text> : null}
      <Button label={saving ? 'Adding…' : 'Add holding'} onPress={() => void save()} loading={saving} />
      <OptionPickerModal
        visible={pickerOpen}
        title="Type"
        options={HOLDING_KINDS}
        selected={kind}
        onSelect={(k) => { setKind(k); setPickerOpen(false); }}
        onClose={() => setPickerOpen(false)}
      />
    </BudgetSheet>
  );
}

const styles = StyleSheet.create({
  menuRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, minHeight: 48, paddingHorizontal: spacing.sm },
  menuText: { fontSize: 15, fontWeight: '600' },
  fieldLabel: { fontSize: 12, fontWeight: '500', marginBottom: 6 },
  picker: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    borderWidth: 1, borderRadius: radius.md, paddingHorizontal: 12, minHeight: 48,
  },
  pickerText: { fontSize: 15, flexShrink: 1 },
  chevron: { fontSize: 20, lineHeight: 20 },
});
