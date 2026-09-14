import { useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useQuery } from '@tanstack/react-query';
import { MetricTile } from '../components/AccountUI';
import { Button } from '../components/Button';
import { ExportDataSheet } from './settings/ExportDataSheet';
import { analyticsApi, userApi } from '../api/endpoints';
import { fmtDate } from '../lib/format';
import { spacing, useTheme } from '../theme';
import type { MoreStackParamList } from '../navigation/types';

export function SettingsDataScreen() {
  const c = useTheme();
  const navigation = useNavigation<NativeStackNavigationProp<MoreStackParamList>>();
  const userQ = useQuery({ queryKey: ['user-settings'], queryFn: () => userApi.get() });
  // Best-effort -- shows "—" for any stat that doesn't load rather than blocking the rest of the
  // screen on it.
  const statsQ = useQuery({ queryKey: ['import-statistics'], queryFn: () => analyticsApi.importStatistics(), retry: false });
  const stats = statsQ.data;

  const [exportOpen, setExportOpen] = useState(false);

  // Google/Apple-linked accounts can't complete Export in-app yet -- routes to the one in-app
  // channel that can actually help instead of leaving the sheet open on a dead end. Same
  // modal-dismiss-race fix as the original SettingsScreen's contactSupportForAccountAction: the
  // sheet renders as a native <Modal> (animationType="slide"), so navigating in the same tick as
  // closing it races the modal's native dismiss against React Navigation's push. A fixed delay
  // matching UIKit's own standard modal-transition duration is the fix available without a native
  // onDismiss listener wired up on both platforms.
  function contactSupportForExport() {
    setExportOpen(false);
    setTimeout(() => navigation.navigate('SupportTickets'), 350);
  }

  return (
    <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content}>
      <View style={styles.tiles}>
        <MetricTile label="Statements" value={stats ? stats.totalStatements.toLocaleString('en-IN') : '—'} />
        <MetricTile label="Transactions" value={stats ? stats.totalTransactionsImported.toLocaleString('en-IN') : '—'} />
        <MetricTile label="Rows Skipped" value={stats ? stats.totalTransactionsSkipped.toLocaleString('en-IN') : '—'} />
        <MetricTile label="Last Import" value={fmtDate(stats?.lastImportedAt) ?? '—'} />
      </View>

      <View style={[styles.exportRow, { borderTopColor: c.border }]}>
        <View style={styles.exportText}>
          <Text style={[styles.fieldLabel, { color: c.ink }]}>Export My Data</Text>
          <Text style={[styles.hint, { color: c.mutedInk }]}>
            A ZIP of everything in your account, including your original statement files.
          </Text>
        </View>
        <Button label="Export" onPress={() => setExportOpen(true)} variant="link" />
      </View>

      {exportOpen ? (
        <ExportDataSheet
          onClose={() => setExportOpen(false)}
          signInMethod={userQ.data?.signInMethod ?? 'PASSWORD'}
          onContactSupport={contactSupportForExport}
        />
      ) : null}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  tiles: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  exportRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    marginTop: spacing.md, paddingTop: spacing.md, borderTopWidth: StyleSheet.hairlineWidth,
  },
  exportText: { flex: 1, marginRight: spacing.sm },
  fieldLabel: { fontSize: 12, fontWeight: '500', marginBottom: 6 },
  hint: { fontSize: 11, lineHeight: 16, marginTop: spacing.sm },
});
