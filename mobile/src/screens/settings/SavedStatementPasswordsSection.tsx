import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '../../components/Button';
import { statementPasswordsApi, type SavedStatementPassword } from '../../api/endpoints';
import { AppAlert } from '../../lib/appAlert';
import { fmtDate } from '../../lib/format';
import { spacing, useTheme } from '../../theme';

const QUERY_KEY = ['saved-statement-passwords'];

/**
 * Settings -> Data -> Saved statement passwords (statement refresh, step 4). Lists the statements
 * the user let Fynora keep a password for -- never the password itself -- and removes one or all.
 * Hidden entirely while saving is switched off and nothing was ever saved.
 */
export function SavedStatementPasswordsSection() {
  const c = useTheme();
  const queryClient = useQueryClient();
  const listQ = useQuery({ queryKey: QUERY_KEY, queryFn: () => statementPasswordsApi.list(), retry: false });
  const [busy, setBusy] = useState(false);
  const [actionFailed, setActionFailed] = useState(false);

  const data = listQ.data;
  if (!listQ.isError && (!data || (!data.saveAvailable && data.items.length === 0))) return null;
  const items = data?.items ?? [];

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setActionFailed(false);
    try {
      await action();
      await queryClient.invalidateQueries({ queryKey: QUERY_KEY });
    } catch {
      setActionFailed(true);
    } finally {
      setBusy(false);
    }
  }

  function confirmRemoveAll() {
    AppAlert.alert(
      'Remove all saved passwords?',
      "Fynora will ask for each statement's password again the next time it needs to read it.",
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Remove all', style: 'destructive', onPress: () => void run(() => statementPasswordsApi.removeAll()) },
      ]
    );
  }

  const meta = (i: SavedStatementPassword) => [
    i.accountName,
    i.periodStart && i.periodEnd ? `${fmtDate(i.periodStart)} – ${fmtDate(i.periodEnd)}` : null,
    `saved ${fmtDate(i.savedAt) ?? ''}`,
  ].filter(Boolean).join(' · ');

  return (
    <View style={[styles.section, { borderTopColor: c.border }]} testID="saved-statement-passwords">
      <Text style={[styles.fieldLabel, { color: c.ink }]}>Saved statement passwords</Text>
      <Text style={[styles.hint, { color: c.mutedInk }]}>
        Passwords you chose to keep so Fynora can read these statements again. They're stored encrypted and
        never shown. Removing one means we'll ask for it the next time the statement is read.
      </Text>
      {listQ.isError ? (
        <Text style={[styles.hint, { color: c.dangerInk }]}>Couldn't load your saved passwords just now.</Text>
      ) : null}
      {data && items.length === 0 ? (
        <Text style={[styles.hint, { color: c.mutedInk }]}>You haven't saved any statement passwords.</Text>
      ) : null}
      {items.map((i) => (
        <View key={i.statementImportId} style={[styles.row, { borderColor: c.border }]}>
          <View style={styles.rowText}>
            <Text style={[styles.fileName, { color: c.ink }]}>{i.fileName}</Text>
            <Text style={[styles.meta, { color: c.mutedInk }]} numberOfLines={1}>{meta(i)}</Text>
          </View>
          <Button
            label="Remove"
            variant="link"
            disabled={busy}
            testID={`remove-saved-password-${i.statementImportId}`}
            onPress={() => void run(() => statementPasswordsApi.remove(i.statementImportId))}
          />
        </View>
      ))}
      {items.length > 0 ? (
        <Button label="Remove all" variant="link" disabled={busy} onPress={confirmRemoveAll} testID="remove-all-saved-passwords" />
      ) : null}
      {actionFailed ? (
        <Text style={[styles.hint, { color: c.dangerInk }]} accessibilityRole="alert">
          Couldn't remove that just now — please try again.
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  section: { marginTop: spacing.md, paddingTop: spacing.md, borderTopWidth: StyleSheet.hairlineWidth, gap: spacing.sm },
  fieldLabel: { fontSize: 12, fontWeight: '500' },
  hint: { fontSize: 11, lineHeight: 16 },
  row: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    borderWidth: StyleSheet.hairlineWidth, borderRadius: 8, paddingHorizontal: spacing.sm, paddingVertical: 6,
  },
  rowText: { flex: 1, marginRight: spacing.sm },
  fileName: { fontSize: 13 },
  meta: { fontSize: 11, marginTop: 2 },
});
