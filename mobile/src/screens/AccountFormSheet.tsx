import { useState } from 'react';
import {
  KeyboardAvoidingView, Pressable, ScrollView, StyleSheet, Text, View
} from 'react-native';
import { AppModal } from '../components/AppModal';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button } from '../components/Button';
import { DateField } from '../components/DateField';
import { TextField } from '../components/TextField';
import { accountsApi, type AccountRequest } from '../api/endpoints';
import type { Account } from '../types';
import { hapticError, hapticSelectionIfChanged, hapticSuccess } from '../lib/haptics';
import { toUserMessage } from '../lib/apiError';
import { reportTransportFailure, requestStartedAt } from '../lib/monitoring';
import { useSingleFlight } from '../lib/useSingleFlight';
import { radius, spacing, useTheme } from '../theme';
import { GlassSurface } from '../components/GlassSurface';
import { isAmountOwedUnknown } from '../lib/amountOwed';
import { BankField } from '../components/BankField';
import { OTHER_BANK_ID, OTHER_BANK_OPTION, toBankOption, type BankOption } from '../lib/bankChoice';

const ACCOUNT_TYPES = ['SAVINGS', 'CREDIT_CARD', 'WALLET', 'INVESTMENT'] as const;
type AccountType = (typeof ACCOUNT_TYPES)[number];

const ACCOUNT_TYPE_LABEL: Record<AccountType, string> = {
  SAVINGS: 'Savings', CREDIT_CARD: 'Credit Card', WALLET: 'Wallet', INVESTMENT: 'Investment',
};

interface Props {
  /** Present = editing that account. Absent = adding a new one. */
  account?: Account | null;
  /** Edit mode only: open the bank list straight away (the "Which bank is this?" prompt). */
  focusBank?: boolean;
  onClose: () => void;
  /** `bankAnswered`: the person picked a bank in this edit, even the one already set ("Other
   *  bank" again counts as an answer to the prompt, spec 2026-10-22 section 7). */
  onSaved: (result?: { bankAnswered: boolean }) => void;
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
 *
 * Edit mode also changes the account's bank (spec 2026-10-22-bank-from-profile-and-required-bank-
 * design.md, section 4): an import whose statement named no bank leaves "Other bank", and this is
 * where the person fixes it. Add mode is unchanged. An Account Aggregator account's bank is shown
 * but not editable: AA sets it once at link time from the bank's own IFSC
 * (AccountAggregatorIdentityResolutionService.detectBankId), and a later re-link finds the account
 * again by its stored bank (ProductIdentityResolver.storedIdentityOf), so a hand-changed bank would
 * stop the sync recognising its own account and create a second one.
 */
export function AccountFormSheet({ account, focusBank, onClose, onSaved }: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const singleFlight = useSingleFlight();
  const isEdit = !!account;

  const [name, setName] = useState(account?.name ?? '');
  const [accountType, setAccountType] = useState<AccountType>(
    (account?.accountType as AccountType | undefined) ?? 'SAVINGS'
  );
  // A card whose amount owed is not known starts blank: its balance was summed from zero and is
  // never shown (lib/amountOwed.ts). Left blank, a save sends no balance and changes nothing.
  const amountOwedUnknown = !!account && isAmountOwedUnknown(account);
  const [balance, setBalance] = useState(account && !amountOwedUnknown ? String(account.balance) : '');
  const [creditLimit, setCreditLimit] = useState(
    account?.creditLimit != null ? String(account.creditLimit) : ''
  );
  const [dueDate, setDueDate] = useState<string | null>(account?.dueDate ?? null);
  const syncedByAa = account?.primarySource === 'ACCOUNT_AGGREGATOR';
  const originalBankId = account?.bank?.id || OTHER_BANK_ID;
  const [bank, setBank] = useState<BankOption>(
    account?.bank ? toBankOption(account.bank) : OTHER_BANK_OPTION
  );
  const [bankAnswered, setBankAnswered] = useState(false);
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
      const startedAt = requestStartedAt();
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
          // A card whose amount owed is not known sends whatever was typed, even the summed figure
          // itself: typing it is the user stating it, which is what makes it known.
          if (balanceNum !== undefined && (amountOwedUnknown || balanceNum !== account.balance)) {
            body.balance = balanceNum;
          }
          // Only a real change is sent, and never for an AA account (see this component's doc).
          if (!syncedByAa && bank.id !== originalBankId) body.bankId = bank.id;
          await accountsApi.update(account.id, body);
        } else {
          body.balance = balanceNum;
          await accountsApi.create(body);
        }
        hapticSuccess();
        onSaved({ bankAnswered: isEdit && !syncedByAa && bankAnswered });
      } catch (e) {
        hapticError();
        reportTransportFailure(e, isEdit ? 'account-form:update' : 'account-form:create', startedAt);
        setError(toUserMessage(e, isEdit ? 'Could not update this account.' : 'Could not add this account.'));
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
          accessibilityLabel={isEdit ? 'Close edit account' : 'Close add account'}
        />
        <GlassSurface style={[styles.sheet, { paddingBottom: insets.bottom + spacing.md }]}>
          <ScrollView keyboardShouldPersistTaps="handled" style={styles.scroll}>
            <Text style={[styles.title, { color: c.ink }]}>{isEdit ? 'Edit Account' : 'Add Account'}</Text>

            <TextField
              label="Account name"
              value={name}
              onChangeText={setName}
              placeholder="e.g. HDFC Savings"
            />

            {isEdit ? (
              syncedByAa ? (
                <View testID="account-form-bank-readonly">
                  <Text style={[styles.sectionLabel, { color: c.muted }]}>Bank</Text>
                  <Text style={[styles.fixedType, { color: c.ink }]}>{bank.name}</Text>
                  <Text style={[styles.note, { color: c.muted }]}>
                    Set by Bank Sync from your bank&apos;s own records, so it can&apos;t be changed here.
                  </Text>
                </View>
              ) : (
                <BankField
                  value={bank}
                  onChange={(b) => { setBank(b); setBankAnswered(true); }}
                  startOpen={focusBank}
                  hint={focusBank && !bankAnswered
                    ? "This account's statement didn't say which bank it's from. Choose it, or Other bank."
                    : null}
                  testID="account-form-bank"
                />
              )
            ) : null}

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
                      onPress={() => { hapticSelectionIfChanged(accountType, t); setAccountType(t); }}
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
              placeholder={amountOwedUnknown ? 'Not known' : '0.00'}
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
                  <Text style={[styles.error, { color: c.dangerInk }]}>
                    A due date can&apos;t be removed here -- pick a different date instead, or
                    contact support.
                  </Text>
                ) : null}
              </>
            ) : null}

            {error ? <Text style={[styles.error, { color: c.dangerInk }]}>{error}</Text> : null}
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
  title: { fontSize: 17, fontWeight: '700', marginBottom: spacing.sm },
  sectionLabel: { fontSize: 11, textTransform: 'uppercase', marginTop: spacing.xs, marginBottom: 4 },
  fixedType: { fontSize: 15, marginBottom: spacing.sm },
  note: { fontSize: 12, marginTop: -4, marginBottom: spacing.sm },
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
