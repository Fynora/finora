import { useState } from 'react';
import {
  KeyboardAvoidingView, Modal, Platform, Pressable, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useQueryClient } from '@tanstack/react-query';
import { Button } from '../components/Button';
import { CategoryPickerModal } from '../components/CategoryPickerModal';
import { DateField } from '../components/DateField';
import { TextField } from '../components/TextField';
import { transactionsApi, type CategoryOption, type UpdateTransactionPayload } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { useSingleFlight } from '../lib/useSingleFlight';
import { radius, spacing, useTheme } from '../theme';
import type { Transaction } from '../types';

interface Props {
  transaction: Transaction;
  onClose: () => void;
  onSaved: () => void;
}

/**
 * Mobile counterpart to frontend/src/pages/Ledger.tsx's EditTransactionModal -- the full edit
 * form (date/amount/merchant/type/category/notes/tags), not just LedgerScreen's own row-tap
 * "change category" shortcut, which only ever calls transactionsApi.updateCategory. Wires
 * transactionsApi.update, which has existed in this file since Phase 0 with zero call sites (the
 * gap this screen closes).
 *
 * notes/tags are sent as their literal current value, never funneled through `|| null` -- the
 * backend's UpdateRequest treats a null field as "leave unchanged," so that would make clearing
 * existing notes down to empty silently no-op instead of actually clearing them. Same reasoning
 * as web's identical comment on this exact point.
 */
export function EditTransactionSheet({ transaction, onClose, onSaved }: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const queryClient = useQueryClient();
  const singleFlight = useSingleFlight();

  const [date, setDate] = useState<string | null>(transaction.date);
  const [description, setDescription] = useState(transaction.description ?? '');
  const [merchant, setMerchant] = useState(transaction.merchant ?? '');
  // Bug fix (found while writing this screen's tests): Transaction.amount is SIGNED in the read
  // model (negative for an EXPENSE -- see LedgerScreen's own Math.abs()+sign-prefix rendering),
  // but TransactionService.requireAmountWithinBounds rejects anything <= 0 on both create AND
  // update -- the backend always expects a positive amount, with `type` conveying direction
  // separately. Seeding this field with the raw signed value (what frontend/src/pages/Ledger.tsx's
  // EditTransactionModal does too) means editing any expense shows a negative number and disables
  // Save permanently, since amountValid below requires > 0. Math.abs() here is the fix; the field
  // now matches what a human -- and the backend -- actually mean by "amount".
  const [amount, setAmount] = useState(String(Math.abs(transaction.amount)));
  const [type, setType] = useState<'INCOME' | 'EXPENSE'>(transaction.type);
  const [category, setCategory] = useState(transaction.categoryName);
  const [notes, setNotes] = useState(transaction.notes ?? '');
  const [tagsInput, setTagsInput] = useState((transaction.tags ?? []).join(', '));
  const [categoryPickerOpen, setCategoryPickerOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const amountValid = amount.length > 0 && Number(amount) > 0;
  const canSave = description.trim().length > 0 && amountValid && date !== null;

  async function save() {
    if (!canSave || !date) return;
    setError(null);
    await singleFlight(async () => {
      setSaving(true);
      try {
        const payload: UpdateTransactionPayload = {
          date,
          description,
          merchant,
          amount: parseFloat(amount),
          type,
          categoryName: category,
          notes: notes.trim(),
          tags: tagsInput.split(',').map((s) => s.trim()).filter(Boolean),
        };
        await transactionsApi.update(transaction.id, payload);
        // Every screen deriving totals/charts from this transaction (Dashboard, Reports,
        // Insights, Budgets) is now stale -- same invalidation Ledger's own delete/recategorize
        // actions already trigger.
        invalidateFinancialData(queryClient);
        onSaved();
      } catch (e) {
        setError(toUserMessage(e, 'Could not save these changes.'));
      } finally {
        setSaving(false);
      }
    });
  }

  return (
    <Modal visible animationType="slide" transparent onRequestClose={saving ? () => {} : onClose}>
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Pressable
          style={styles.backdrop}
          onPress={saving ? undefined : onClose}
          disabled={saving}
          accessibilityLabel="Close edit transaction"
        />
        <View style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}>
          <ScrollView keyboardShouldPersistTaps="handled" style={styles.scroll}>
            <Text style={[styles.title, { color: c.ink }]}>Edit Transaction</Text>

            <DateField label="Date" value={date} onChange={setDate} />

            <Text style={[styles.sectionLabel, { color: c.muted }]}>Type</Text>
            <View style={styles.typeRow}>
              {(['EXPENSE', 'INCOME'] as const).map((t) => (
                <Pressable
                  key={t}
                  onPress={() => setType(t)}
                  accessibilityRole="button"
                  accessibilityState={{ selected: type === t }}
                  style={[
                    styles.typeChip,
                    { borderColor: c.border },
                    type === t && { backgroundColor: c.primaryLight, borderColor: c.primary },
                  ]}
                >
                  <Text style={[styles.typeChipText, { color: type === t ? c.primary : c.muted }]}>
                    {t === 'EXPENSE' ? 'Expense' : 'Income'}
                  </Text>
                </Pressable>
              ))}
            </View>

            <TextField label="Description" value={description} onChangeText={setDescription} />
            <TextField label="Merchant" value={merchant} onChangeText={setMerchant} />
            <TextField
              label="Amount"
              value={amount}
              onChangeText={setAmount}
              keyboardType="decimal-pad"
              error={amount.length > 0 && !amountValid ? 'Amount must be greater than zero.' : null}
            />

            <Text style={[styles.sectionLabel, { color: c.muted }]}>Category</Text>
            <Pressable
              onPress={() => setCategoryPickerOpen(true)}
              style={[styles.picker, { borderColor: c.border }]}
              accessibilityRole="button"
              accessibilityLabel="Category"
            >
              <Text style={[styles.pickerValue, { color: c.ink }]}>{category}</Text>
            </Pressable>

            <TextField label="Notes" value={notes} onChangeText={setNotes} multiline />
            <TextField
              label="Tags (comma-separated)"
              value={tagsInput}
              onChangeText={setTagsInput}
              placeholder="e.g. shared, recurring"
              autoCapitalize="none"
            />

            {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}
            <View style={styles.action}>
              <Button label={saving ? 'Saving…' : 'Save Changes'} onPress={() => void save()} loading={saving} disabled={!canSave} />
              <Button label="Cancel" variant="link" onPress={onClose} disabled={saving} />
            </View>
          </ScrollView>
        </View>
      </KeyboardAvoidingView>

      <CategoryPickerModal
        visible={categoryPickerOpen}
        selectedName={category}
        onSelect={(c2: CategoryOption) => setCategory(c2.name)}
        onClose={() => setCategoryPickerOpen(false)}
      />
    </Modal>
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
  title: { fontSize: 17, fontWeight: '700', marginBottom: spacing.sm },
  sectionLabel: { fontSize: 11, textTransform: 'uppercase', marginTop: spacing.sm, marginBottom: 4 },
  typeRow: { flexDirection: 'row', gap: spacing.sm, marginBottom: spacing.sm },
  typeChip: {
    flex: 1, borderWidth: 1, borderRadius: radius.md, paddingVertical: 10, alignItems: 'center',
  },
  typeChipText: { fontSize: 13, fontWeight: '600' },
  picker: { borderWidth: 1, borderRadius: radius.md, padding: 12, marginBottom: spacing.xs },
  pickerValue: { fontSize: 15 },
  error: { fontSize: 13, marginTop: spacing.xs },
  action: { marginTop: spacing.md, gap: spacing.xs, marginBottom: spacing.sm },
});
