import { useState } from 'react';
import {
  KeyboardAvoidingView, Modal, Platform, Pressable, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button } from '../components/Button';
import { DateField } from '../components/DateField';
import { TextField } from '../components/TextField';
import { accountsApi, type AccountRequest } from '../api/endpoints';
import type { Account } from '../types';
import { toUserMessage } from '../lib/apiError';
import { useSingleFlight } from '../lib/useSingleFlight';
import { radius, spacing, useTheme } from '../theme';

const ACCOUNT_TYPES = ['SAVINGS', 'CREDIT_CARD', 'WALLET', 'INVESTMENT'] as const;
type AccountType = (typeof ACCOUNT_TYPES)[number];

const ACCOUNT_TYPE_LABEL: Record<AccountType, string> = {
  SAVINGS: 'Savings', CREDIT_CARD: 'Credit Card', WALLET: 'Wallet', INVESTMENT: 'Investment',
};

interface Props {
  /** Present = editing that account. Absent = adding a new one. */
  account?: Account | null;
  onClose: () => void;
  onSaved: () => void;
}

/**
 * Phase 4 (Medium-Tier Parity). accountsApi.create/update/remove and AccountRequest have existed
 * since before this session with zero UI call sites -- AccountsScreen was read-only by design
 * (see its own former doc comment). Web has no single page to port either: accountsApi.create is
 * only ever called from Setup.tsx's onboarding wizard and Investments.tsx's holdings form, neither
 * of which is a general-purpose "add/edit an account" screen. This is a fresh, deliberately small
 * design -- name, type, opening balance, and (for a credit card) limit/due date -- not a port of
 * Setup.tsx's full bank-search/holder-name/branch/IFSC form, which belongs to the statement-import
 * detection path (DetectedAccountInfo), not manual entry.
 *
 * Account type is fixed once created: AccountService.update() never applies a changed
 * accountType even though CreateRequest's shape requires the field (see that method's own doc
 * comment -- it simply never calls a.setAccountType(...)). The type chips are only interactive in
 * add mode; editing an existing account shows the type as a plain, non-interactive label instead
 * of a picker that would silently do nothing.
 */
export function AccountFormSheet({ account, onClose, onSaved }: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const singleFlight = useSingleFlight();
  const isEdit = !!account;

  const [name, setName] = useState(account?.name ?? '');
  const [accountType, setAccountType] = useState<AccountType>(
    (account?.accountType as AccountType | undefined) ?? 'SAVINGS'
  );
  const [balance, setBalance] = useState(account ? String(account.balance) : '');
  const [creditLimit, setCreditLimit] = useState(
    account?.creditLimit != null ? String(account.creditLimit) : ''
  );
  const [dueDate, setDueDate] = useState<string | null>(account?.dueDate ?? null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const nameValid = name.trim().length > 0;
  const balanceValid = balance.trim().length === 0 || !Number.isNaN(Number(balance));
  const creditLimitValid = creditLimit.trim().length === 0 || !Number.isNaN(Number(creditLimit));
  // AccountService.update() only ever APPLIES a non-null dueDate (`if (req.dueDate() != null)
  // a.setDueDate(...)`) -- there is no way through this endpoint to clear one that was already
  // set. Submitting after "Clear" would silently do nothing server-side while the form looks like
  // it succeeded, so that combination is blocked here instead of shipping a false affordance.
  const dueDateClearBlocked = account?.dueDate != null && accountType === 'CREDIT_CARD' && dueDate === null;
  const canSave = nameValid && balanceValid && creditLimitValid && !dueDateClearBlocked;

  async function save() {
    if (!canSave) return;
    setError(null);
    await singleFlight(async () => {
      setSaving(true);
      try {
        const balanceNum = balance.trim().length > 0 ? Number(balance) : undefined;
        const body: AccountRequest = {
          name: name.trim(),
          accountType,
          // A blanked creditLimit sends undefined, which update() treats as "leave it alone" --
          // same as an unchanged balance below -- rather than actually clearing it server-side.
          // There's no explicit "Clear" affordance on this field the way DateField has for
          // dueDate (see dueDateClearBlocked above), so an emptied field here just quietly keeps
          // its old value instead of creating a false promise the way a Clear button would.
          ...(accountType === 'CREDIT_CARD' ? {
            creditLimit: creditLimit.trim().length > 0 ? Number(creditLimit) : undefined,
            dueDate: dueDate ?? undefined,
          } : {}),
        };
        if (isEdit) {
          // Only sent when it actually changed. AccountService.update() treats ANY balance
          // present in the payload as a fresh, fully-trusted manual baseline and invalidates the
          // account's statement-reconciliation lineage (see that method's own doc comment on
          // lastAbsoluteSetStatementId) -- resending the unchanged value on every plain rename
          // would silently break reconciliation for something the user never touched.
          if (balanceNum !== undefined && balanceNum !== account.balance) {
            body.balance = balanceNum;
          }
          await accountsApi.update(account.id, body);
        } else {
          body.balance = balanceNum;
          await accountsApi.create(body);
        }
        onSaved();
      } catch (e) {
        setError(toUserMessage(e, isEdit ? 'Could not update this account.' : 'Could not add this account.'));
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
          accessibilityLabel={isEdit ? 'Close edit account' : 'Close add account'}
        />
        <View style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}>
          <ScrollView keyboardShouldPersistTaps="handled" style={styles.scroll}>
            <Text style={[styles.title, { color: c.ink }]}>{isEdit ? 'Edit Account' : 'Add Account'}</Text>

            <TextField
              label="Account name"
              value={name}
              onChangeText={setName}
              placeholder="e.g. HDFC Savings"
            />

            <Text style={[styles.sectionLabel, { color: c.muted }]}>Account type</Text>
            {isEdit ? (
              <Text style={[styles.fixedType, { color: c.ink }]}>
                {ACCOUNT_TYPE_LABEL[accountType]}
              </Text>
            ) : (
              <View style={styles.typeRow}>
                {ACCOUNT_TYPES.map((t) => {
                  const active = accountType === t;
                  return (
                    <Pressable
                      key={t}
                      onPress={() => setAccountType(t)}
                      accessibilityRole="button"
                      accessibilityState={{ selected: active }}
                      style={[
                        styles.typeChip,
                        { borderColor: active ? c.primary : c.border, backgroundColor: active ? c.primaryLight : 'transparent' },
                      ]}
                    >
                      <Text style={[styles.typeText, { color: active ? c.primary : c.muted }]}>
                        {ACCOUNT_TYPE_LABEL[t]}
                      </Text>
                    </Pressable>
                  );
                })}
              </View>
            )}

            <TextField
              label={isEdit ? 'Balance' : 'Opening balance'}
              value={balance}
              onChangeText={setBalance}
              placeholder="0.00"
              keyboardType="decimal-pad"
              error={!balanceValid ? 'Enter a valid amount.' : null}
            />

            {accountType === 'CREDIT_CARD' ? (
              <>
                <TextField
                  label="Credit limit"
                  value={creditLimit}
                  onChangeText={setCreditLimit}
                  placeholder="0.00"
                  keyboardType="decimal-pad"
                  error={!creditLimitValid ? 'Enter a valid amount.' : null}
                />
                <DateField label="Payment due date" value={dueDate} onChange={setDueDate} />
                {dueDateClearBlocked ? (
                  <Text style={[styles.error, { color: c.danger }]}>
                    A due date can&apos;t be removed here -- pick a different date instead, or
                    contact support.
                  </Text>
                ) : null}
              </>
            ) : null}

            {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}
            <View style={styles.action}>
              <Button
                label={saving ? 'Saving…' : isEdit ? 'Save Changes' : 'Add Account'}
                onPress={() => void save()}
                loading={saving}
                disabled={!canSave}
              />
              <Button label="Cancel" variant="link" onPress={onClose} disabled={saving} />
            </View>
          </ScrollView>
        </View>
      </KeyboardAvoidingView>
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
  sectionLabel: { fontSize: 11, textTransform: 'uppercase', marginTop: spacing.xs, marginBottom: 4 },
  fixedType: { fontSize: 15, marginBottom: spacing.sm },
  typeRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginBottom: spacing.sm },
  typeChip: {
    borderWidth: 1,
    borderRadius: 999,
    paddingHorizontal: 14,
    minHeight: 40,
    justifyContent: 'center',
  },
  typeText: { fontSize: 12, fontWeight: '600' },
  error: { fontSize: 13, marginTop: spacing.xs },
  action: { marginTop: spacing.sm, gap: spacing.xs, marginBottom: spacing.sm },
});
