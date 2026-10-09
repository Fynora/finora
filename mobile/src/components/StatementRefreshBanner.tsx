import { useState, type ReactNode } from 'react';
import {
  KeyboardAvoidingView, Pressable, ScrollView, StyleSheet, Switch, Text, TextInput, View,
} from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  statementRefreshApi, type RefreshPendingStatement, type RefreshRowView, type RefreshRunDetail,
} from '../api/endpoints';
import { AppModal } from './AppModal';
import { Button } from './Button';
import { Card } from './Card';
import { invalidateFinancialQueries } from '../lib/invalidateFinancialData';
import { counts, describeChange, fieldValue, inr, outcomeLabel, period } from '../lib/refreshFormat';
import { fmtDate } from '../lib/format';
import { radius, spacing, useTheme } from '../theme';
import { GlassSurface } from './GlassSurface';

export const REFRESH_OVERVIEW_KEY = ['statement-refresh-overview'];

/**
 * Statement refresh, step 5: the in-app banner. Shown only when the latest check found statements
 * an improved parser would read differently. One tap updates them all -- applied straight away, with
 * no review step, then a "what changed" summary. A protected PDF whose password was not saved is
 * listed separately and asks for it. Renders nothing while refreshing is switched off.
 */
export function StatementRefreshBanner() {
  const c = useTheme();
  const queryClient = useQueryClient();
  const { data } = useQuery({ queryKey: REFRESH_OVERVIEW_KEY, queryFn: statementRefreshApi.overview, retry: false });
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const [results, setResults] = useState<RefreshRunDetail[] | null>(null);
  const [asking, setAsking] = useState<RefreshPendingStatement | null>(null);

  if (!data?.enabled) return null;
  const updatable = data.updatable;
  const locked = data.needsPassword;
  const showBanner = updatable.length > 0 || locked.length > 0;
  if (!showBanner && !results) return null;

  function afterChanges() {
    // The financial cascade includes this banner's own query, the statement list and every figure a
    // corrected row can move.
    invalidateFinancialQueries(queryClient);
  }

  async function updateAll() {
    setBusy(true);
    setFailed(false);
    const all: RefreshRunDetail[] = [];
    try {
      // Ten at a time server-side; keep going while more remain and the last call made progress.
      for (let round = 0; round < 20; round++) {
        const res = await statementRefreshApi.applyAll();
        all.push(...res.results);
        if (res.remaining === 0 || res.results.length === 0) break;
      }
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
      if (all.length > 0) {
        setResults(all);
        afterChanges();
      }
    }
  }

  return (
    <>
      {showBanner ? (
        <Card style={{ ...styles.banner, borderColor: c.primary }} testID="statement-refresh-banner">
          <Text style={[styles.title, { color: c.ink }]}>
            {updatable.length > 0
              ? `We now read ${updatable.length === 1 ? 'one' : updatable.length} of your statements more accurately`
              : 'Some of your statements need their password to be checked'}
          </Text>
          <Text style={[styles.body, { color: c.muted }]}>
            {updatable.length > 0
              ? "Updating corrects them from the files you already uploaded — nothing is re-uploaded, and your own edits, categories and notes are kept. You'll see exactly what changed."
              : "We improved how statements are read, but these are password protected, so we couldn't check them."}
          </Text>
          {updatable.length > 0 ? (
            <Button
              label={busy ? 'Updating…' : `Update ${updatable.length} ${updatable.length === 1 ? 'statement' : 'statements'}`}
              onPress={() => void updateAll()}
              loading={busy}
              disabled={busy}
              testID="statement-refresh-update-all"
            />
          ) : null}
          {failed ? (
            <Text style={[styles.body, { color: c.dangerInk }]} accessibilityRole="alert">
              Something went wrong while updating — please try again.
            </Text>
          ) : null}
          {locked.length > 0 ? (
            <View style={[styles.locked, { borderTopColor: c.border }]}>
              <Text style={[styles.body, { color: c.muted }]}>
                {locked.length === 1 ? 'This statement is' : 'These statements are'} password protected:
              </Text>
              {locked.map((s) => (
                <View key={s.statementImportId} style={styles.lockedRow}>
                  <View style={styles.flex}>
                    <Text style={[styles.fileName, { color: c.ink }]}>{s.fileName}</Text>
                    <Text style={[styles.meta, { color: c.mutedInk }]} numberOfLines={1}>
                      {[s.accountName, period(s.periodStart, s.periodEnd)].filter(Boolean).join(' · ')}
                    </Text>
                  </View>
                  <Button label="Enter password" variant="link" disabled={busy} onPress={() => setAsking(s)}
                    testID={`statement-refresh-password-${s.statementImportId}`} />
                </View>
              ))}
            </View>
          ) : null}
        </Card>
      ) : null}

      {asking ? (
        <RefreshPasswordSheet
          statement={asking}
          savePasswordAvailable={data.savePasswordAvailable}
          onClose={() => setAsking(null)}
          onDone={(detail) => {
            setAsking(null);
            setResults([detail]);
            afterChanges();
          }}
        />
      ) : null}

      {results ? <RefreshSummarySheet results={results} onClose={() => setResults(null)} /> : null}
    </>
  );
}

/** The password for one protected statement; kept only if the user switches "keep" on. */
function RefreshPasswordSheet({
  statement, savePasswordAvailable, onClose, onDone,
}: {
  statement: RefreshPendingStatement;
  savePasswordAvailable: boolean;
  onClose: () => void;
  onDone: (detail: RefreshRunDetail) => void;
}) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const [password, setPassword] = useState('');
  const [revealed, setRevealed] = useState(false);
  const [keep, setKeep] = useState(false);
  const [busy, setBusy] = useState(false);
  const [wrong, setWrong] = useState(false);
  const [failed, setFailed] = useState(false);

  async function submit() {
    setBusy(true);
    setWrong(false);
    setFailed(false);
    try {
      const outcome = await statementRefreshApi.refreshOne(statement.statementImportId, password,
        savePasswordAvailable && keep);
      if (outcome.status === 'NEEDS_PASSWORD') {
        setWrong(true);
        return;
      }
      onDone(outcome.runId
        ? await statementRefreshApi.run(outcome.runId)
        : { ...emptyDetail(statement), status: outcome.status, reason: outcome.reason });
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <AppModal visible transparent animationType="slide" onRequestClose={busy ? () => {} : onClose}>
      <KeyboardAvoidingView style={styles.flex} behavior="padding">
        <Pressable style={styles.backdrop} onPress={busy ? undefined : onClose} disabled={busy}
          accessibilityLabel="Close" />
        <GlassSurface style={[styles.sheet, { paddingBottom: insets.bottom + spacing.md }]}
          testID="refresh-password-sheet">
          <Text style={[styles.title, { color: c.ink }]}>Update this statement</Text>
          <Text style={[styles.body, { color: c.muted }]}>
            <Text style={{ color: c.ink }}>{statement.fileName}</Text> is password protected. Enter the
            password your bank uses for it.
          </Text>
          <View style={[styles.passwordRow, { borderColor: wrong ? c.danger : c.border, backgroundColor: c.inputBg }]}>
            <TextInput
              value={password}
              onChangeText={setPassword}
              secureTextEntry={!revealed}
              autoCapitalize="none"
              autoCorrect={false}
              autoFocus
              autoComplete="off"
              textContentType="none"
              accessibilityLabel="Statement password"
              editable={!busy}
              style={[styles.passwordInput, { color: c.ink }]}
            />
            <Pressable onPress={() => setRevealed((r) => !r)} hitSlop={8} disabled={busy} accessibilityRole="button"
              accessibilityLabel={revealed ? 'Hide password' : 'Show password'}>
              <Text style={[styles.toggle, { color: c.primary }]}>{revealed ? 'Hide' : 'Show'}</Text>
            </Pressable>
          </View>
          <Text style={[styles.meta, { color: wrong || failed ? c.dangerInk : c.mutedInk }]}>
            {wrong
              ? "That password didn't open this statement — check it and try again."
              : failed ? 'Something went wrong — please try again.' : 'The password your bank uses for this statement.'}
          </Text>
          {savePasswordAvailable ? (
            <View style={styles.keepRow}>
              <View style={styles.flex}>
                <Text style={[styles.fileName, { color: c.ink }]}>Keep this password so future updates don't need it</Text>
                <Text style={[styles.meta, { color: c.mutedInk }]}>
                  Stored encrypted, only for this statement. Remove it any time in Settings → Data.
                </Text>
              </View>
              <Switch
                value={keep}
                onValueChange={setKeep}
                disabled={busy}
                trackColor={{ true: c.primary, false: c.border }}
                thumbColor={keep ? c.onPrimary : undefined}
                accessibilityLabel="Keep this password"
                testID="refresh-keep-password"
              />
            </View>
          ) : null}
          <Button label={busy ? 'Updating…' : 'Update statement'} onPress={() => void submit()}
            disabled={!password || busy} loading={busy} testID="refresh-password-submit" />
          <Button label="Cancel" variant="link" onPress={onClose} disabled={busy} />
        </GlassSurface>
      </KeyboardAvoidingView>
    </AppModal>
  );
}

/** What updating changed, statement by statement -- shown straight after, since nothing is reviewed first. */
function RefreshSummarySheet({ results, onClose }: { results: RefreshRunDetail[]; onClose: () => void }) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const updated = results.filter((r) => r.status === 'APPLIED').length;

  return (
    <AppModal visible transparent animationType="slide" onRequestClose={onClose}>
      <View style={styles.flex}>
        <Pressable style={styles.backdrop} onPress={onClose} accessibilityLabel="Close what changed" />
        <GlassSurface style={[styles.sheet, styles.tall, { paddingBottom: insets.bottom + spacing.md }]}
          testID="refresh-summary">
          <Text style={[styles.title, { color: c.ink }]}>What changed</Text>
          <Text style={[styles.body, { color: c.muted }]}>
            {updated === 0
              ? 'None of your statements needed changes.'
              : `${updated} ${updated === 1 ? 'statement was' : 'statements were'} updated. Your own edits, categories and notes were kept.`}
          </Text>
          <ScrollView style={styles.flex} contentContainerStyle={{ gap: spacing.sm }}>
            {results.map((r) => <ResultCard key={r.runId ?? r.statementImportId} result={r} />)}
          </ScrollView>
          <Button label="Done" onPress={onClose} />
        </GlassSurface>
      </View>
    </AppModal>
  );
}

function ResultCard({ result: r }: { result: RefreshRunDetail }) {
  const c = useTheme();
  const heading = [r.accountName, period(r.periodStart, r.periodEnd)].filter(Boolean).join(' · ');
  const ok = r.status === 'APPLIED' || r.status === 'NO_CHANGES';
  return (
    <View style={[styles.result, { borderColor: c.border }]} testID="refresh-result">
      <Text style={[styles.fileName, { color: c.ink }]}>{r.fileName ?? 'Statement'}</Text>
      {heading ? <Text style={[styles.meta, { color: c.mutedInk }]}>{heading}</Text> : null}
      <Text style={[styles.body, { color: ok ? c.ink : c.warningInk }]}>
        {outcomeLabel(r)}{r.status === 'APPLIED' ? counts(r) : ''}
      </Text>
      {r.balanceChange !== null && r.balanceChange !== undefined && Number(r.balanceChange) !== 0 ? (
        <Text style={[styles.meta, { color: c.mutedInk }]}>Account balance changed by {inr(r.balanceChange)}</Text>
      ) : null}
      {r.changed.length > 0 ? (
        <Section title="Corrected">
          {r.changed.map((ch, i) => (
            <View key={ch.transactionId ?? i}>
              <RowLine row={ch} />
              {ch.changes.map((f, j) => (
                <Text key={j} style={[styles.meta, styles.indent, { color: c.mutedInk }]}>{describeChange(f)}</Text>
              ))}
            </View>
          ))}
        </Section>
      ) : null}
      {r.added.length > 0 ? (
        <Section title="Added — rows we had missed">
          {r.added.map((a, i) => <RowLine key={a.transactionId ?? i} row={a} />)}
        </Section>
      ) : null}
      {r.removed.length > 0 ? (
        <Section title="Removed — not on the statement">
          {r.removed.map((m, i) => (
            <View key={m.transactionId ?? i}>
              <RowLine row={m} />
              {m.userEdited ? (
                <Text style={[styles.meta, styles.indent, { color: c.warningInk }]}>You had edited this row</Text>
              ) : null}
            </View>
          ))}
        </Section>
      ) : null}
      {r.skippedAsDuplicate.length > 0 ? (
        <Section title="Not added — already imported from another statement">
          {r.skippedAsDuplicate.map((s, i) => <RowLine key={i} row={s} />)}
        </Section>
      ) : null}
      {r.facts.length > 0 ? (
        <Section title="Statement details">
          {r.facts.map((f, i) => <Text key={i} style={[styles.meta, { color: c.ink }]}>{describeChange(f)}</Text>)}
        </Section>
      ) : null}
    </View>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  const c = useTheme();
  return (
    <View style={styles.section}>
      <Text style={[styles.sectionTitle, { color: c.ink }]}>{title}</Text>
      {children}
    </View>
  );
}

function RowLine({ row }: { row: RefreshRowView }) {
  const c = useTheme();
  return (
    <Text style={[styles.meta, { color: c.ink }]}>
      {row.date ? fmtDate(row.date) : '—'} · {row.description ?? '—'} · {fieldValue('AMOUNT', row.amount)}
    </Text>
  );
}

function emptyDetail(s: RefreshPendingStatement): RefreshRunDetail {
  return {
    runId: null, statementImportId: s.statementImportId, fileName: s.fileName, accountName: s.accountName,
    periodStart: s.periodStart, periodEnd: s.periodEnd, status: 'NO_CHANGES', createdAt: null,
    rowsChanged: 0, rowsAdded: 0, rowsRemoved: 0, factsChanged: 0, balanceChange: null, reason: null,
    changed: [], added: [], removed: [], skippedAsDuplicate: [], facts: [],
  };
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  banner: { gap: spacing.sm, marginBottom: spacing.md },
  title: { fontSize: 15, fontWeight: '600' },
  body: { fontSize: 13, lineHeight: 18 },
  locked: { borderTopWidth: StyleSheet.hairlineWidth, paddingTop: spacing.sm, gap: spacing.xs },
  lockedRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  fileName: { fontSize: 13, fontWeight: '500' },
  meta: { fontSize: 11, lineHeight: 16 },
  indent: { marginLeft: spacing.sm },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.4)' },
  sheet: {
    borderTopLeftRadius: radius.lg, borderTopRightRadius: radius.lg,
    padding: spacing.md, gap: spacing.sm,
  },
  tall: { maxHeight: '85%' },
  passwordRow: {
    flexDirection: 'row', alignItems: 'center', borderWidth: 1, borderRadius: radius.md, paddingHorizontal: spacing.sm,
  },
  passwordInput: { flex: 1, fontSize: 15, paddingVertical: 12 },
  toggle: { fontSize: 13, fontWeight: '600', paddingLeft: 8 },
  keepRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  result: { borderWidth: StyleSheet.hairlineWidth, borderRadius: radius.md, padding: spacing.sm, gap: 4 },
  section: { marginTop: spacing.xs, gap: 2 },
  sectionTitle: { fontSize: 12, fontWeight: '600' },
});
