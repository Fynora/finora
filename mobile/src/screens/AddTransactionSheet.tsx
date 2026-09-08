import { useRef, useState } from 'react';
import {
  KeyboardAvoidingView, Modal, Platform, Pressable, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '../components/Button';
import { CategoryPickerModal } from '../components/CategoryPickerModal';
import { DateField } from '../components/DateField';
import { TextField } from '../components/TextField';
import {
  accountsApi, transactionsApi, type CategoryOption, type CreateTransactionPayload,
} from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { newIdempotencyKey } from '../lib/idempotencyKey';
import { useSingleFlight } from '../lib/useSingleFlight';
import { radius, spacing, useTheme } from '../theme';

interface Props {
  onClose: () => void;
  onSaved: () => void;
}

/**
 * Mobile counterpart to frontend/src/components/AddTransactionModal.tsx -- wires
 * transactionsApi.create(), which has existed in this file since Phase 0 with zero call sites.
 * Deliberately close to EditTransactionSheet's own field layout, minus the fields CreateRequest
 * doesn't have (merchant is derived server-side from description; notes isn't part of creation)
 * and plus the one it needs that update doesn't: which account this goes on, since a transaction
 * always belongs to one -- see CreateTransactionPayload's own doc comment.
 */
export function AddTransactionSheet({ onClose, onSaved }: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const queryClient = useQueryClient();
  const singleFlight = useSingleFlight();

  const accountsQ = useQuery({ queryKey: ['accounts'], queryFn: () => accountsApi.list() });
  const accounts = accountsQ.data ?? [];
  const hasAccount = accountsQ.isSuccess && accounts.length > 0;

  const [accountId, setAccountId] = useState<string | null>(null);
  const [accountPickerOpen, setAccountPickerOpen] = useState(false);
  const [date, setDate] = useState<string | null>(() => new Date().toISOString().slice(0, 10));
  const [description, setDescription] = useState('');
  const [amount, setAmount] = useState('');
  const [type, setType] = useState<'INCOME' | 'EXPENSE'>('EXPENSE');
  const [category, setCategory] = useState<string | null>(null);
  const [categoryPickerOpen, setCategoryPickerOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Minted once per attempt and reused on retry -- see AddTransactionModal.tsx's identical
  // comment: if the first request never reached the server the key is unused and the retry
  // proceeds; if it committed, the server returns the ORIGINAL transaction instead of creating a
  // second one and moving the account balance twice. Cleared only on success.
  const attemptKey = useRef<string | null>(null);

  // accountId starts null (not accounts[0]?.id) specifically because accounts loads
  // asynchronously -- seeding it eagerly would freeze in the empty string the very first render
  // sees, before the real list ever arrives.
  const selectedAccountId = accountId ?? accounts[0]?.id ?? null;
  const selectedAccount = accounts.find((a) => a.id === selectedAccountId) ?? null;
  const amountValid = amount.length > 0 && Number(amount) > 0;
  const canSave = hasAccount && !!selectedAccountId && description.trim().length > 0 && amountValid && date !== null;

  async function save() {
    if (!canSave || !selectedAccountId || !date) return;
    setError(null);
    if (attemptKey.current === null) attemptKey.current = newIdempotencyKey();
    await singleFlight(async () => {
      setSaving(true);
      try {
        const payload: CreateTransactionPayload = {
          accountId: selectedAccountId,
          idempotencyKey: attemptKey.current!,
          date,
          description: description.trim(),
          amount: parseFloat(amount),
          type,
          categoryName: category,
          tags: [],
        };
        await transactionsApi.create(payload);
        attemptKey.current = null;
        invalidateFinancialData(queryClient);
        onSaved();
      } catch (e) {
        setError(toUserMessage(e, 'Could not add this transaction.'));
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
          accessibilityLabel="Close add transaction"
        />
        <View style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}>
          <ScrollView keyboardShouldPersistTaps="handled" style={styles.scroll}>
            <Text style={[styles.title, { color: c.ink }]}>Add Transaction</Text>

            {accountsQ.isLoading ? (
              <Text style={[styles.body, { color: c.muted }]}>Loading your accounts…</Text>
            ) : !hasAccount ? (
              // A transaction always belongs to an account -- there is nothing to attach one to
              // yet. Mobile has no manual add-account flow of its own (AccountsScreen is
              // read-only by design -- accounts are created by importing a statement), so this
              // points there instead of a Setup screen that doesn't exist here.
              <Text style={[styles.body, { color: c.ink }]}>
                Import a statement first — a transaction always has to belong to an account.
              </Text>
            ) : (
              <>
                <Text style={[styles.sectionLabel, { color: c.muted }]}>Account</Text>
                <Pressable
                  onPress={() => setAccountPickerOpen(true)}
                  style={[styles.picker, { borderColor: c.border }]}
                  accessibilityRole="button"
                  accessibilityLabel="Account"
                >
                  <Text style={[styles.pickerValue, { color: c.ink }]}>{selectedAccount?.name}</Text>
                </Pressable>

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

                <TextField
                  label="Description"
                  value={description}
                  onChangeText={setDescription}
                  placeholder="e.g. Groceries at the market"
                />
                <TextField
                  label="Amount"
                  value={amount}
                  onChangeText={setAmount}
                  placeholder="0.00"
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
                  <Text style={[styles.pickerValue, { color: category ? c.ink : c.muted }]}>
                    {category ?? 'Let Fynora categorize it'}
                  </Text>
                </Pressable>

                {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}
                <View style={styles.action}>
                  <Button label={saving ? 'Adding…' : 'Add Transaction'} onPress={() => void save()} loading={saving} disabled={!canSave} />
                  <Button label="Cancel" variant="link" onPress={onClose} disabled={saving} />
                </View>
              </>
            )}
          </ScrollView>
        </View>
      </KeyboardAvoidingView>

      <CategoryPickerModal
        visible={categoryPickerOpen}
        selectedName={category}
        onSelect={(c2: CategoryOption) => setCategory(c2.name)}
        onClose={() => setCategoryPickerOpen(false)}
        onSelectedCategoryDeleted={() => setCategory(null)}
      />

      <Modal visible={accountPickerOpen} animationType="slide" transparent onRequestClose={() => setAccountPickerOpen(false)}>
        {/* Hidden from assistive tech -- same reasoning as OptionPickerModal's identical backdrop:
            a pointer-only convenience, not an accessible control (there's a real Cancel-equivalent
            via the hardware back button / onRequestClose). */}
        <Pressable
          style={styles.backdrop}
          onPress={() => setAccountPickerOpen(false)}
          accessible={false}
          accessibilityElementsHidden
          importantForAccessibility="no-hide-descendants"
        />
        <View style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md, maxHeight: '60%' }]} accessibilityViewIsModal>
          <Text style={[styles.title, { color: c.ink }]} accessibilityRole="header">Account</Text>
          <ScrollView>
            {accounts.map((a) => (
              <Pressable
                key={a.id}
                onPress={() => { setAccountId(a.id); setAccountPickerOpen(false); }}
                style={[styles.accountRow, { borderBottomColor: c.border }]}
                accessibilityRole="button"
              >
                <Text style={[styles.pickerValue, { color: a.id === selectedAccountId ? c.primary : c.ink }]}>
                  {a.name}
                </Text>
              </Pressable>
            ))}
          </ScrollView>
        </View>
      </Modal>
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
  body: { fontSize: 14, lineHeight: 20 },
  sectionLabel: { fontSize: 11, textTransform: 'uppercase', marginTop: spacing.sm, marginBottom: 4 },
  typeRow: { flexDirection: 'row', gap: spacing.sm, marginBottom: spacing.sm },
  typeChip: {
    flex: 1, borderWidth: 1, borderRadius: radius.md, paddingVertical: 10, alignItems: 'center',
  },
  typeChipText: { fontSize: 13, fontWeight: '600' },
  picker: { borderWidth: 1, borderRadius: radius.md, padding: 12, marginBottom: spacing.xs },
  pickerValue: { fontSize: 15 },
  accountRow: { paddingVertical: 14, borderBottomWidth: StyleSheet.hairlineWidth },
  error: { fontSize: 13, marginTop: spacing.xs },
  action: { marginTop: spacing.md, gap: spacing.xs, marginBottom: spacing.sm },
});
