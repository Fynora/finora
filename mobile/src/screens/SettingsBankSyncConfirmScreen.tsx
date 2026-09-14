import { useEffect, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, View } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { Button } from '../components/Button';
import { OptionPickerModal } from '../components/OptionPickerModal';
import { accountsApi, accountAggregatorApi } from '../api/endpoints';
import type { Account } from '../types';
import { toUserMessage } from '../lib/apiError';
import { spacing, useTheme } from '../theme';
import type { MoreStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<MoreStackParamList, 'SettingsBankSyncConfirm'>;

/**
 * Ports frontend/src/pages/AccountAggregatorConfirm.tsx -- offers a picker over the user's own
 * existing accounts rather than a suggested match. AccountAggregatorIdentityResolutionService's
 * PROBABLE-match candidates are a local variable on the backend, never persisted onto the link or
 * exposed by any endpoint, so there is nothing to suggest; confirmExistingAccount only ever took
 * an accountId the caller supplies. Same reasoning as the web page's own doc comment.
 */
export function SettingsBankSyncConfirmScreen({ route, navigation }: Props) {
  const c = useTheme();
  const linkId = route.params.linkId;

  const [accounts, setAccounts] = useState<Account[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [selectedAccountId, setSelectedAccountId] = useState('');
  const [pickerOpen, setPickerOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    accountsApi.list()
      .then((list) => { setAccounts(list); if (list.length > 0) setSelectedAccountId(list[0].id); })
      .catch(() => setLoadError(true))
      .finally(() => setLoading(false));
  }, []);

  async function confirmExisting() {
    if (!selectedAccountId) return;
    setBusy(true);
    setActionError(null);
    try {
      await accountAggregatorApi.confirmExistingAccount(linkId, selectedAccountId);
      navigation.goBack();
    } catch (e) {
      setActionError(toUserMessage(e, "Couldn't confirm this account."));
      setBusy(false);
    }
  }

  async function confirmNew() {
    setBusy(true);
    setActionError(null);
    try {
      await accountAggregatorApi.confirmNewAccount(linkId);
      navigation.goBack();
    } catch (e) {
      setActionError(toUserMessage(e, "Couldn't set this up as a new account."));
      setBusy(false);
    }
  }

  const selected = accounts.find((a) => a.id === selectedAccountId);
  const accountLabel = (a: Account) => `${a.name} · ${a.bank.shortName}${a.accountNumberMasked ? ` ${a.accountNumberMasked}` : ''}`;

  return (
    <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content}>
      <Text style={[styles.title, { color: c.ink }]}>Confirm your bank account</Text>
      <Text style={[styles.hint, { color: c.mutedInk }]}>
        We connected a bank account through Account Aggregator, but couldn't automatically match it
        to one of your existing accounts.
      </Text>
      {loading ? (
        <ActivityIndicator color={c.primary} />
      ) : (
        <View>
          {loadError ? (
            <Text style={[styles.hint, { color: c.danger }]}>
              Couldn't load your existing accounts, but you can still set this up as a new one below.
            </Text>
          ) : accounts.length > 0 && selected ? (
            <View style={styles.field}>
              <Text style={[styles.label, { color: c.mutedInk }]}>Which of your accounts is this?</Text>
              <Button label={accountLabel(selected)} variant="link" onPress={() => setPickerOpen(true)} />
            </View>
          ) : null}
          {actionError ? <Text style={[styles.hint, { color: c.danger }]}>{actionError}</Text> : null}
          {!loadError && accounts.length > 0 && (
            <Button label="Yes, this is my account" onPress={() => void confirmExisting()} loading={busy} />
          )}
          <Button label="This is a different/new account" variant="link" onPress={() => void confirmNew()} loading={busy} />
        </View>
      )}
      <OptionPickerModal
        visible={pickerOpen}
        title="Which account?"
        options={accounts.map(accountLabel)}
        selected={selected ? accountLabel(selected) : ''}
        onSelect={(label) => {
          setSelectedAccountId(accounts.find((a) => accountLabel(a) === label)?.id ?? selectedAccountId);
          setPickerOpen(false);
        }}
        onClose={() => setPickerOpen(false)}
      />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  title: { fontSize: 20, fontWeight: '700', marginBottom: spacing.xs },
  hint: { fontSize: 13, marginBottom: spacing.md },
  field: { marginBottom: spacing.md },
  label: { fontSize: 12, marginBottom: spacing.xs },
});
